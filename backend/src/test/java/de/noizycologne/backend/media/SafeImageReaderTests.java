package de.noizycologne.backend.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeImageReaderTests {
    @TempDir Path root;
    private byte[] image(String format) throws Exception {
        var bytes=new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),format,bytes)).isTrue();
        return bytes.toByteArray();
    }
    @Test void readsSinglePngAndJpegWithoutChangingSourceBytes() throws Exception {
        for(String format:java.util.List.of("png","jpeg")) {
            byte[] bytes=image(format); Path file=Files.write(root.resolve("image."+format),bytes);
            BufferedImage read=SafeImageReader.read(file,8_000_000);
            assertThat(read.getWidth()).isEqualTo(2); assertThat(read.getHeight()).isEqualTo(3);
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
            try(var prepared=new ImagePreprocessor().prepare(file)) { assertThat(prepared.image("gray-2x").path()).exists(); }
        }
    }
    @Test void rejectsGigapixelPngAndJpegHeadersInASmallHeapChildJvm() throws Exception {
        byte[] png=image("png"); ByteBuffer.wrap(png).putInt(16,30_000).putInt(20,30_000);
        var crc=new CRC32(); crc.update(png,12,17); ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
        assertSmallHeapRejects(Files.write(root.resolve("bomb.png"),png));
        byte[] jpeg=image("jpeg"); boolean changed=false;
        for(int index=0;index<jpeg.length-9;index++) {
            if((jpeg[index]&255)==255 && (jpeg[index+1]&255)==192) {
                ByteBuffer.wrap(jpeg).putShort(index+5,(short)30_000).putShort(index+7,(short)30_000); changed=true; break;
            }
        }
        assertThat(changed).isTrue();
        assertSmallHeapRejects(Files.write(root.resolve("bomb.jpg"),jpeg));
    }
    private void assertSmallHeapRejects(Path source) throws Exception {
        String classpath=Objects.requireNonNullElse(System.getProperty("surefire.test.class.path"),System.getProperty("java.class.path",""));
        Path output=root.resolve(source.getFileName()+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m","-cp",classpath,
            SafeImageReaderFixture.class.getName(),source.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();
            Objects.requireNonNull(assertThat(process.exitValue()).withFailMessage("%s",Files.readString(output))).isZero();
            assertThat(Files.readString(output)).contains("PIXELS_REJECTED_BEFORE_DECODE");
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    @Test void rejectsAnimatedPngEvenWhenImageIoOnlyExposesItsFirstFrame() throws Exception {
        byte[] original=image("png"); var out=new ByteArrayOutputStream(); out.write(original,0,33);
        byte[] payload=ByteBuffer.allocate(12).putInt(0x6163544c).putInt(2).putInt(0).array();
        out.write(ByteBuffer.allocate(4).putInt(8).array()); out.write(payload);
        var crc=new CRC32(); crc.update(payload); out.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());
        out.write(original,33,original.length-33);
        Path file=Files.write(root.resolve("animated.png"),out.toByteArray());
        Objects.requireNonNull(assertThatThrownBy(() -> SafeImageReader.read(file,8_000_000)).isInstanceOf(IOException.class)).hasMessageContaining("Animated");
    }
    @Test void rejectsMultipleJpegsAndIncompleteOrUnsupportedSources() throws Exception {
        byte[] jpeg=image("jpeg"); var out=new ByteArrayOutputStream(); out.write(jpeg); out.write(jpeg);
        Path multi=Files.write(root.resolve("multi.jpg"),out.toByteArray());
        Objects.requireNonNull(assertThatThrownBy(() -> SafeImageReader.read(multi,8_000_000)).isInstanceOf(IOException.class)).hasMessageContaining("Multi-frame");
        Path truncated=Files.write(root.resolve("truncated.png"),Arrays.copyOf(image("png"),38));
        assertThatThrownBy(() -> SafeImageReader.read(truncated,8_000_000)).isInstanceOf(IOException.class);
        Path unsupported=Files.writeString(root.resolve("invalid.png"),"not an image");
        assertThatThrownBy(() -> SafeImageReader.read(unsupported,8_000_000)).isInstanceOf(IOException.class);
        Path linked=root.resolve("link.png"); Files.createSymbolicLink(linked,unsupported);
        assertThatThrownBy(() -> SafeImageReader.read(linked,8_000_000)).isInstanceOf(IOException.class);
        Path real=Files.createDirectory(root.resolve("real")); Files.write(real.resolve("valid.png"),image("png"));
        Path linkedParent=root.resolve("linked-parent"); Files.createSymbolicLink(linkedParent,real);
        assertThatThrownBy(() -> SafeImageReader.read(linkedParent.resolve("valid.png"),8_000_000)).isInstanceOf(IOException.class);
    }
    @Test void rejectsFilesAboveTheActualByteBudget() throws Exception {
        Path large=root.resolve("large.png");
        try(var file=new java.io.RandomAccessFile(large.toFile(),"rw")) { file.setLength(10L*1024*1024+1); }
        Objects.requireNonNull(assertThatThrownBy(() -> SafeImageReader.read(large,8_000_000)).isInstanceOf(IOException.class)).hasMessageContaining("oversized");
    }
}
