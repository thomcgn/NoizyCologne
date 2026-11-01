package de.noizycologne.backend.media;

import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Creates a bounded set of OCR-friendly variants without changing the original asset. */
@Component
public class ImagePreprocessor {
    private static final int MAX_PIXELS = 40_000_000;
    private static final int MAX_PROCESSING_DIMENSION = 2400;
    private static final int MAX_VARIANTS = 6;

    public PreparedImages prepare(Path source) {
        try {
            BufferedImage original = ImageIO.read(source.toFile());
            if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
                throw new IllegalArgumentException("Der Flyer konnte nicht als Bild gelesen werden.");
            }
            if ((long) original.getWidth() * original.getHeight() > MAX_PIXELS) {
                throw new IllegalArgumentException("Der Flyer überschreitet die zulässige Pixelzahl.");
            }
            BufferedImage working = fitForProcessing(original);
            double processingScale = (double) original.getWidth() / working.getWidth();
            Path directory = Files.createTempDirectory("noizy-ocr-");
            List<PreparedImage> images = new ArrayList<>();
            images.add(new PreparedImage(source, "original", 1.0, false));
            images.add(write(directory, "gray-2x", scale(grayscale(working), 2), 2.0 * processingScale));
            images.add(write(directory, "contrast-2x", scale(contrast(working), 2), 2.0 * processingScale));
            images.add(write(directory, "otsu-2x", scale(otsu(grayscale(working)), 2), 2.0 * processingScale));
            images.add(write(directory, "adaptive-2x", scale(adaptive(grayscale(working)), 2), 2.0 * processingScale));
            images.add(write(directory, "invert-2x", scale(invert(grayscale(working)), 2), 2.0 * processingScale));
            return new PreparedImages(directory, images.subList(0, Math.min(MAX_VARIANTS, images.size())));
        } catch (IOException exception) {
            throw new IllegalStateException("Der Flyer konnte für OCR nicht vorbereitet werden.", exception);
        }
    }

    private PreparedImage write(Path directory, String variant, BufferedImage image, double scale) throws IOException {
        Path target = directory.resolve(variant + ".png");
        ImageIO.write(image, "png", target.toFile());
        return new PreparedImage(target, variant, scale, true);
    }

    private BufferedImage fitForProcessing(BufferedImage source) {
        int longestSide = Math.max(source.getWidth(), source.getHeight());
        if (longestSide <= MAX_PROCESSING_DIMENSION) return source;
        double factor = (double) MAX_PROCESSING_DIMENSION / longestSide;
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }

    private BufferedImage grayscale(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return result;
    }

    private BufferedImage contrast(BufferedImage source) {
        BufferedImage result = grayscale(source);
        for (int y = 0; y < result.getHeight(); y++) for (int x = 0; x < result.getWidth(); x++) {
            int value = result.getRaster().getSample(x, y, 0);
            result.getRaster().setSample(x, y, 0, Math.max(0, Math.min(255, (value - 128) * 2 + 128)));
        }
        return result;
    }

    private BufferedImage otsu(BufferedImage source) {
        int[] histogram = histogram(source);
        int total = source.getWidth() * source.getHeight();
        long sum = 0;
        for (int i = 0; i < histogram.length; i++) sum += (long) i * histogram[i];
        long backgroundWeight = 0, backgroundSum = 0;
        double bestVariance = -1;
        int threshold = 128;
        for (int i = 0; i < histogram.length; i++) {
            backgroundWeight += histogram[i];
            if (backgroundWeight == 0) continue;
            long foregroundWeight = total - backgroundWeight;
            if (foregroundWeight == 0) break;
            backgroundSum += (long) i * histogram[i];
            double backgroundMean = (double) backgroundSum / backgroundWeight;
            double foregroundMean = (double) (sum - backgroundSum) / foregroundWeight;
            double variance = backgroundWeight * (double) foregroundWeight * Math.pow(backgroundMean - foregroundMean, 2);
            if (variance > bestVariance) { bestVariance = variance; threshold = i; }
        }
        return threshold(source, threshold);
    }

    private BufferedImage adaptive(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        int radius = Math.max(8, Math.min(24, Math.min(source.getWidth(), source.getHeight()) / 40));
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int sum = 0, count = 0;
            for (int yy = Math.max(0, y - radius); yy <= Math.min(source.getHeight() - 1, y + radius); yy += 2)
                for (int xx = Math.max(0, x - radius); xx <= Math.min(source.getWidth() - 1, x + radius); xx += 2) {
                    sum += source.getRaster().getSample(xx, yy, 0); count++;
                }
            int localThreshold = (sum / Math.max(1, count)) - 8;
            result.getRaster().setSample(x, y, 0, source.getRaster().getSample(x, y, 0) < localThreshold ? 0 : 255);
        }
        return result;
    }

    private BufferedImage invert(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            result.getRaster().setSample(x, y, 0, 255 - source.getRaster().getSample(x, y, 0));
        return result;
    }

    private BufferedImage threshold(BufferedImage source, int threshold) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            result.getRaster().setSample(x, y, 0, source.getRaster().getSample(x, y, 0) >= threshold ? 255 : 0);
        return result;
    }

    private int[] histogram(BufferedImage source) {
        int[] histogram = new int[256];
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            histogram[source.getRaster().getSample(x, y, 0)]++;
        return histogram;
    }

    private BufferedImage scale(BufferedImage source, int factor) {
        BufferedImage result = new BufferedImage(source.getWidth() * factor, source.getHeight() * factor, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
        graphics.dispose();
        return result;
    }

    public record PreparedImage(Path path, String variant, double scale, boolean temporary) { }

    public record PreparedImages(Path directory, List<PreparedImage> images) implements AutoCloseable {
        @Override public void close() {
            try {
                for (PreparedImage image : images) if (image.temporary()) Files.deleteIfExists(image.path());
                Files.deleteIfExists(directory);
            } catch (IOException ignored) { }
        }
    }
}
