package de.noizycologne.backend.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TesseractOcrServiceTests {
    @TempDir Path root;
    private static final String VALID="import sys\nsys.stdout.write('1\\t1\\t1\\t1\\t1\\t1\\t10\\t20\\t30\\t40\\t95\\tEBM\\n')\n";

    private TesseractOcrService worker(String body,long timeout) throws Exception {
        Path command=Files.writeString(root.resolve(UUID.randomUUID()+".py"),"#!/usr/bin/env python3\n"+body);
        Files.setPosixFilePermissions(command,PosixFilePermissions.fromString("rwx------"));
        return new TesseractOcrService(command.toString(),"fixture",timeout);
    }
    private ImagePreprocessor.PreparedImage image() { return new ImagePreprocessor.PreparedImage(root.resolve("fixture.png"),"original",1,false); }
    private void assertAvailable() throws Exception {
        var result=worker(VALID,1).recognize(image(),11,1);
        assertThat(result.text()).isEqualTo("EBM"); assertThat(result.words()).hasSize(1);
    }

    @Test void validTsvIsParsedWithoutChangingTheOriginalImage() throws Exception { assertAvailable(); }

    @Test void timeoutKillsTheWorkerAndReleasesTheSlot() throws Exception {
        long start=System.nanoTime();
        var service=worker("import time,os,pathlib,sys\npathlib.Path(sys.argv[1]+'.pid').write_text(str(os.getpid()))\ntime.sleep(30)\n",1);
        assertThatThrownBy(() -> service.recognize(image(),11,1)).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(504));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(6));
        long pid=Long.parseLong(Files.readString(root.resolve("fixture.png.pid")));
        assertThat(ProcessHandle.of(pid).map(handle -> java.util.Objects.requireNonNull(handle).isAlive()).orElse(false)).isFalse();
        assertAvailable();
    }

    @Test void outputLimitKillsImmediatelyAndReleasesTheSlot() throws Exception {
        var service=worker("import sys,time\nsys.stdout.buffer.write(b'x'*(1024*1024+1))\nsys.stdout.flush()\ntime.sleep(30)\n",5);
        long start=System.nanoTime();
        assertThatThrownBy(() -> service.recognize(image(),11,1)).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(5));
        assertAvailable();
    }

    @Test void excessRequestsFailWithoutAnUnboundedQueueAndInterruptReleasesTheSlot() throws Exception {
        var service=worker("import pathlib,sys,time\npathlib.Path(sys.argv[1]+'.started').write_text('ready')\ntime.sleep(30)\n",10);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(() -> service.recognize(image(),11,1));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                while(!Files.exists(root.resolve("fixture.png.started")) && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(Files.exists(root.resolve("fixture.png.started"))).isTrue();
                assertThatThrownBy(() -> service.recognize(image(),11,2)).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(429));
            } finally {
                pending.cancel(true); executor.shutdownNow();
                assertThat(executor.awaitTermination(6,TimeUnit.SECONDS)).isTrue();
            }
        }
        assertAvailable();
    }

    @Test void failedStartAndNonzeroExitDoNotLeakTheSlot() throws Exception {
        var missing=new TesseractOcrService(root.resolve("missing").toString(),"fixture",1);
        assertThatThrownBy(() -> missing.recognize(image(),11,1)).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
        var failure=worker("raise SystemExit(3)\n",1);
        assertThatThrownBy(() -> failure.recognize(image(),11,1)).isInstanceOf(ResponseStatusException.class);
        assertAvailable();
    }

    @Test void oversizedLinesAndWordCountFailExplicitlyInsteadOfInventingPartialResults() throws Exception {
        var longLine=worker("print('x'*4097)\n",1);
        assertThatThrownBy(() -> longLine.recognize(image(),11,1)).hasMessageContaining("Ausgabezeile");
        var manyWords=worker("import sys\nsys.stdout.write('1\\t1\\t1\\t1\\t1\\t1\\t10\\t20\\t30\\t40\\t95\\tEBM\\n'*10001)\n",1);
        assertThatThrownBy(() -> manyWords.recognize(image(),11,1)).hasMessageContaining("zu viele");
        assertAvailable();
    }
}
