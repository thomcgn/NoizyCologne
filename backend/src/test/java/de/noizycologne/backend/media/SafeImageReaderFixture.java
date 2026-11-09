package de.noizycologne.backend.media;

import java.io.IOException;
import java.nio.file.Path;

/** Small-heap child JVM proves the hostile header is rejected before pixel allocation. */
public final class SafeImageReaderFixture {
    private SafeImageReaderFixture() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("fixture path required");
        try { SafeImageReader.read(Path.of(args[0]),8_000_000); }
        catch(IOException exception) {
            if(exception.getMessage().contains("pixel budget")) {
                System.out.println("PIXELS_REJECTED_BEFORE_DECODE"); return;
            }
            throw exception;
        }
        throw new IllegalStateException("Hostile dimensions accepted");
    }
}
