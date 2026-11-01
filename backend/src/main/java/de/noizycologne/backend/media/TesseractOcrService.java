package de.noizycologne.backend.media;

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
    private final String command;
    private final String languages;
    private final long timeoutMillis;

    public TesseractOcrService(@Value("${noizy.ai.tesseract.command:tesseract}") String command,
                               @Value("${noizy.ai.tesseract.languages:deu+eng}") String languages,
                               @Value("${noizy.ai.tesseract.timeout-seconds:30}") long timeoutSeconds) {
        this.command = command;
        this.languages = languages;
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000L;
    }

    public OcrResult recognize(ImagePreprocessor.PreparedImage image, int psm, int pass) {
        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "noizy-tesseract-output");
            thread.setDaemon(true);
            return thread;
        });
        boolean acquired = false;
        try {
            OCR_SLOT.acquire();
            acquired = true;
            ProcessBuilder processBuilder = new ProcessBuilder(command, image.path().toString(), "stdout", "--tsv", "-l", languages,
                    "--oem", "1", "--psm", Integer.toString(psm)).redirectErrorStream(true);
            processBuilder.environment().put("OMP_THREAD_LIMIT", "1");
            process = processBuilder.start();
            Process running = process;
            Future<String> output = reader.submit(() -> new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Die lokale Flyer-Texterkennung hat zu lange gedauert.");
            }
            String tsv = output.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Die lokale Flyer-Texterkennung ist nicht verfügbar.");
            return parse(tsv, image, psm, pass);
        } catch (ResponseStatusException exception) { throw exception;
        } catch (IOException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Tesseract ist auf dem Backend nicht installiert oder nicht ausführbar.", exception);
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT, "Die lokale Flyer-Texterkennung wurde unterbrochen.", exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tesseract hat kein vollständiges OCR-Ergebnis geliefert.", exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            reader.shutdownNow();
            if (acquired) OCR_SLOT.release();
        }
    }

    private OcrResult parse(String tsv, ImagePreprocessor.PreparedImage image, int psm, int pass) {
        List<OcrWord> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double confidenceSum = 0;
        int confidenceCount = 0;
        for (String line : tsv.split("\\R")) {
            String[] fields = line.split("\\t", -1);
            if (fields.length < 12 || "text".equals(fields[11]) || fields[11].isBlank()) continue;
            try {
                double confidence = Double.parseDouble(fields[10]);
                String value = fields[11].trim();
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
