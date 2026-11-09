package de.noizycologne.backend.media;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TesseractFlyerGenreAnalyzerTests {
    @Test void wholePipelineIsBoundedBeforeDecodingAndFailureReleasesAdmission() throws Exception {
        var processor=mock(ImagePreprocessor.class); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(processor.prepare(any(Path.class))).thenAnswer(invocation -> {
            entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            throw new IllegalArgumentException("fixture decoding failure");
        });
        var service=new TesseractFlyerGenreAnalyzer(processor,mock(TesseractOcrService.class),mock(GenreLexicon.class));
        var asset=mock(MediaAsset.class); Path path=Path.of("fixture.png");
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(() -> assertThatThrownBy(() -> service.analyze(asset,path)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422)));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.analyze(asset,path)).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(429));
                verify(processor,times(1)).prepare(path);
            } finally { release.countDown(); }
            first.get(5,TimeUnit.SECONDS);
        }
        assertThatThrownBy(() -> service.analyze(asset,path)).isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
        verify(processor,times(2)).prepare(path);
    }
}
