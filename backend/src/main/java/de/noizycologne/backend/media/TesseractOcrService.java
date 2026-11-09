package de.noizycologne.backend.media;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

@Service
public class TesseractOcrService {
    private static final Semaphore OCR_SLOT = new Semaphore(1);
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_WORDS = 10_000;
    private static final java.util.concurrent.atomic.AtomicBoolean WORKER_UNHEALTHY = new java.util.concurrent.atomic.AtomicBoolean();
    private final String command;
    private final String languages;
    private final long timeoutMillis;

    public TesseractOcrService(@Value("${noizy.ai.tesseract.command:tesseract}") String command,
                               @Value("${noizy.ai.tesseract.languages:deu+eng}") String languages,
                               @Value("${noizy.ai.tesseract.timeout-seconds:30}") long timeoutSeconds) {
        this.command = command;
        this.languages = languages;
        this.timeoutMillis = Math.max(1, Math.min(60, timeoutSeconds)) * 1000L;
    }

    public OcrResult recognize(ImagePreprocessor.PreparedImage image, int psm, int pass) {
        if (WORKER_UNHEALTHY.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Die OCR-Arbeitsumgebung muss vom Administrator geprüft werden.");
        if (!OCR_SLOT.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Die lokale Flyer-Texterkennung ist belegt. Bitte danach erneut versuchen.");
        Process process = null;
        ExecutorService reader = null;
        try {
            reader = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "noizy-tesseract-output");
                thread.setDaemon(true);
                return thread;
            });
            ProcessBuilder processBuilder = new ProcessBuilder(command, image.path().toString(), "stdout", "-l", languages,
                    "--oem", "1", "--psm", Integer.toString(psm), "tsv").redirectErrorStream(true);
            processBuilder.environment().put("OMP_THREAD_LIMIT", "1");
            process = processBuilder.start();
            Process running = process;
            Future<@NonNull String> output = reader.submit(() -> readBounded(running));
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Die lokale Flyer-Texterkennung hat zu lange gedauert.");
            }
            String tsv = java.util.Objects.requireNonNull(output.get(2, TimeUnit.SECONDS), "Tesseract lieferte keinen Ausgabetext");
            if (process.exitValue() != 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Die lokale Flyer-Texterkennung ist nicht verfügbar.");
            return parse(tsv, image, psm, pass);
        } catch (ResponseStatusException exception) { throw exception;
        } catch (IOException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Tesseract ist auf dem Backend nicht installiert oder nicht ausführbar.", exception);
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT, "Die lokale Flyer-Texterkennung wurde unterbrochen.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OutputLimitException)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Die OCR-Ausgabe überschreitet das sichere Größenlimit.", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tesseract hat kein vollständiges OCR-Ergebnis geliefert.", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tesseract hat kein vollständiges OCR-Ergebnis geliefert.", exception);
        } finally {
            if (finish(process, reader)) OCR_SLOT.release();
            else WORKER_UNHEALTHY.set(true); // Never launch another worker while an old one might still run.
        }
    }

    private static @NonNull String readBounded(Process process) throws IOException {
        try (var input = process.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_OUTPUT_BYTES + 1);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                destroyTree(process);
                throw new OutputLimitException();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final class OutputLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private static boolean finish(Process process, ExecutorService reader) {
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null && process.isAlive()) destroyTree(process);
            if (reader != null) reader.shutdownNow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((process != null && process.isAlive()) || (reader != null && !reader.isTerminated())) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    if (process != null && process.isAlive()) process.waitFor(remaining, TimeUnit.NANOSECONDS);
                    remaining = deadline - System.nanoTime();
                    if (reader != null && remaining > 0) reader.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) { interrupted = true; }
            }
            return true;
        } finally {
            // Closing a pipe from another thread can block on its reader monitor. Never do that here.
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private OcrResult parse(String tsv, ImagePreprocessor.PreparedImage image, int psm, int pass) {
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double confidenceSum = 0;
        int confidenceCount = 0;
        for (String line : tsv.split("\\R")) {
            if (line.length() > 4096) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Eine OCR-Ausgabezeile ist zu lang.");
            String[] fields = line.split("\\t", -1);
            if (fields.length < 12 || "text".equals(fields[11]) || fields[11].isBlank()) continue;
            try {
                double confidence = Double.parseDouble(fields[10]);
                String value = fields[11].trim();
                if (words.size() >= MAX_WORDS || value.length() > 1024)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Die OCR-Ausgabe enthält zu viele oder zu lange Wörter.");
                if (!Double.isFinite(confidence)) continue;
                words.add(new OcrWord(value, confidence, (int) (Integer.parseInt(fields[6]) / image.scale()),
                        (int) (Integer.parseInt(fields[7]) / image.scale()), (int) (Integer.parseInt(fields[8]) / image.scale()),
                        (int) (Integer.parseInt(fields[9]) / image.scale()), image.variant(), psm, pass,
                        Integer.parseInt(fields[2]), Integer.parseInt(fields[4])));
                if (confidence >= 0) { confidenceSum += confidence; confidenceCount++; }
                if (!text.isEmpty()) text.append(' ');
                text.append(value);
            } catch (NumberFormatException ignored) { }
        }
        return new OcrResult(text.toString(), List.copyOf(words), confidenceCount == 0 ? 0 : confidenceSum / confidenceCount);
    }

    public record OcrResult(String text, List<OcrWord> words, double averageConfidence) {
        public boolean isUnclear() { return words.size() < 5 || averageConfidence < 45; }
    }

    public record OcrWord(String text, double confidence, int left, int top, int width, int height,
                          String variant, int psm, int pass, int block, int line) { }
}
