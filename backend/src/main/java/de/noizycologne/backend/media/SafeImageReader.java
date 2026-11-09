package de.noizycologne.backend.media;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Header/size/frame checks precede every full OCR allocation; originals remain unchanged. */
final class SafeImageReader {
    private static final long MAX_BYTES=10L*1024*1024;
    private SafeImageReader() { }

    static BufferedImage read(Path source,int maxPixels) throws IOException {
        Path absolute=source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)
                || !absolute.toRealPath().equals(absolute) || Files.size(source)<=0 || Files.size(source)>MAX_BYTES)
            throw new IOException("Unsupported or oversized OCR source");
        try(var input=new FileImageInputStream(source.toFile())) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IOException("Unsupported image");
            ImageReader reader=Objects.requireNonNull(readers.next());
            try {
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg"))
                    throw new IOException("Only single-frame PNG/JPEG supported");
                reader.setInput(input,false,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0 || height<=0 || (long)width*height>maxPixels)
                    throw new IOException("OCR pixel budget exceeded before decoding");
                if(reader.getNumImages(true)!=1) throw new IOException("Multi-frame OCR source");
                if(format.equals("png")) rejectAnimatedPng(source);
                BufferedImage image=Objects.requireNonNull(reader.read(0));
                if(image.getWidth()!=width || image.getHeight()!=height) {
                    image.flush(); throw new IOException("Image dimensions changed during decoding");
                }
                return image;
            } finally { reader.dispose(); }
        }
    }

    // ImageIO's PNG reader ignores APNG's extra frames; inspect the bounded container too.
    private static void rejectAnimatedPng(Path source) throws IOException {
        try(var input=new DataInputStream(Files.newInputStream(source))) {
            if(input.readLong()!=0x89504e470d0a1a0aL) throw new IOException("Invalid PNG signature");
            long remaining=Files.size(source)-8;
            for(int chunks=0;chunks<2048;chunks++) {
                if(remaining<12) throw new IOException("Incomplete PNG container");
                long length=Integer.toUnsignedLong(input.readInt()); int type=input.readInt();
                if(length>remaining-12) throw new IOException("Invalid PNG chunk size");
                if(type==0x6163544c) throw new IOException("Animated PNG is not supported for OCR");
                input.skipNBytes(length+4); remaining-=length+12;
                if(type==0x49454e44) {
                    if(length!=0 || remaining!=0) throw new IOException("Invalid PNG ending");
                    return;
                }
            }
            throw new IOException("PNG chunk budget exceeded");
        }
    }
}
