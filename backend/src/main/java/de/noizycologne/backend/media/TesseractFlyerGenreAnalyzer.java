package de.noizycologne.backend.media;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TesseractFlyerGenreAnalyzer implements FlyerGenreAnalyzer {

    private final ImagePreprocessor imagePreprocessor;
    private final TesseractOcrService ocrService;
    private final GenreLexicon genreLexicon;

    public TesseractFlyerGenreAnalyzer(ImagePreprocessor imagePreprocessor,
                                       TesseractOcrService ocrService,
                                       GenreLexicon genreLexicon) {
        this.imagePreprocessor = imagePreprocessor;
        this.ocrService = ocrService;
        this.genreLexicon = genreLexicon;
    }

    @Override
    public FlyerGenreAnalysisResponse analyze(MediaAsset asset, Path imagePath) {
        try (ImagePreprocessor.PreparedImages prepared = imagePreprocessor.prepare(imagePath)) {
            Map<String, TesseractOcrService.OcrWord> bestWords = new java.util.LinkedHashMap<>();
            TesseractOcrService.OcrResult base = ocrService.recognize(prepared.images().get(0), 11, 1);
            merge(bestWords, base.words());

            List<ImagePreprocessor.PreparedImage> fallbacks = new ArrayList<>();
            if (base.isUnclear()) {
                fallbacks.add(prepared.images().get(1));
                fallbacks.add(prepared.images().get(2));
            }
            if (base.words().size() < 3) {
                fallbacks.add(prepared.images().get(3));
                fallbacks.add(prepared.images().get(4));
            }
            int pass = 2;
            for (ImagePreprocessor.PreparedImage image : fallbacks) {
                int psm = image.variant().startsWith("gray") ? 6 : 11;
                TesseractOcrService.OcrResult result = ocrService.recognize(image, psm, pass++);
                merge(bestWords, result.words());
                if (bestWords.size() >= 100) break;
            }

            List<String> genres = genreLexicon.findGenres(List.copyOf(bestWords.values()));
            double confidence = confidence(bestWords.values());
            return new FlyerGenreAnalysisResponse(genres.stream()
                    .map(genre -> new FlyerGenreAnalysisResponse.GenreSuggestion(genre, confidence))
                    .toList());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Der Flyer konnte offline nicht zuverlässig analysiert werden.", exception);
        }
    }

    private void merge(Map<String, TesseractOcrService.OcrWord> bestWords,
                       List<TesseractOcrService.OcrWord> words) {
        for (TesseractOcrService.OcrWord word : words) {
            String key = word.left() + ":" + word.top() + ":" + word.text().toLowerCase(Locale.ROOT);
            TesseractOcrService.OcrWord existing = bestWords.get(key);
            if (existing == null || word.confidence() > existing.confidence()) {
                bestWords.put(key, word);
            }
        }
    }

    private double confidence(Iterable<TesseractOcrService.OcrWord> words) {
        double sum = 0;
        int count = 0;
        for (TesseractOcrService.OcrWord word : words) {
            if (word.confidence() >= 0) {
                sum += word.confidence();
                count++;
            }
        }
        return count == 0 ? 0.0 : Math.min(1.0, Math.max(0.0, (sum / count) / 100.0));
    }
}
