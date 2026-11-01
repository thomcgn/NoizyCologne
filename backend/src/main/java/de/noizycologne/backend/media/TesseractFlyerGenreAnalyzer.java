package de.noizycologne.backend.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class TesseractFlyerGenreAnalyzer implements FlyerGenreAnalyzer {

    private static final Map<String, List<String>> GENRE_ALIASES = Map.ofEntries(
            Map.entry("Rock", List.of("rock")),
            Map.entry("Alternative", List.of("alternative")),
            Map.entry("Indie", List.of("indie")),
            Map.entry("Punk", List.of("punk")),
            Map.entry("Punkrock", List.of("punkrock", "punk rock")),
            Map.entry("Garage Rock", List.of("garage rock", "garage-rock")),
            Map.entry("Rock’n’Roll", List.of("rock'n'roll", "rock n roll", "rock'n roll")),
            Map.entry("Emo", List.of("emo")),
            Map.entry("Hardcore", List.of("hardcore")),
            Map.entry("Metal", List.of("metal")),
            Map.entry("Metalcore", List.of("metalcore", "metal core")),
            Map.entry("Heavy Metal", List.of("heavy metal")),
            Map.entry("Thrash Metal", List.of("thrash metal", "thrash-metal")),
            Map.entry("Death Metal", List.of("death metal", "death-metal")),
            Map.entry("Black Metal", List.of("black metal", "black-metal")),
            Map.entry("Doom", List.of("doom")),
            Map.entry("Hard Rock", List.of("hard rock", "hard-rock")),
            Map.entry("Psych", List.of("psych", "psychedelic")),
            Map.entry("Ska", List.of("ska")),
            Map.entry("Soul", List.of("soul")),
            Map.entry("Post-Punk", List.of("post-punk", "post punk")),
            Map.entry("Darkwave", List.of("darkwave", "dark wave")),
            Map.entry("Gothic", List.of("gothic")),
            Map.entry("Britpop", List.of("britpop")),
            Map.entry("Singer-Songwriter", List.of("singer-songwriter", "singer songwriter")),
            Map.entry("Folk", List.of("folk")),
            Map.entry("Pop", List.of("pop")),
            Map.entry("Sonstiges", List.of("sonstiges", "other"))
    );

    private final String command;
    private final String languages;
    private final Duration timeout;

    public TesseractFlyerGenreAnalyzer(
            @Value("${noizy.ai.tesseract.command:tesseract}") String command,
            @Value("${noizy.ai.tesseract.languages:deu+eng}") String languages,
            @Value("${noizy.ai.tesseract.timeout-seconds:30}") long timeoutSeconds) {
        this.command = command;
        this.languages = languages;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public FlyerGenreAnalysisResponse analyze(MediaAsset asset, Path imagePath) {
        String text = runTesseract(imagePath);
        Map<String, FlyerGenreAnalysisResponse.GenreSuggestion> suggestions = new LinkedHashMap<>();
        String normalizedText = normalize(text);

        GENRE_ALIASES.forEach((genre, aliases) -> {
            for (String alias : aliases) {
                if (containsPhrase(normalizedText, normalize(alias))) {
                    suggestions.put(genre, new FlyerGenreAnalysisResponse.GenreSuggestion(genre, 0.95));
                    break;
                }
            }
        });

        return new FlyerGenreAnalysisResponse(new ArrayList<>(suggestions.values()));
    }

    private String runTesseract(Path imagePath) {
        Process process;
        try {
            process = new ProcessBuilder(
                    command, imagePath.toString(), "stdout", "-l", languages, "--psm", "11"
            ).redirectErrorStream(false).start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Die lokale Flyer-Texterkennung hat zu lange gedauert.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Die lokale Flyer-Texterkennung ist nicht verfügbar.");
            }
            return output;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Tesseract ist auf dem Backend nicht installiert oder nicht ausführbar.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Die lokale Flyer-Texterkennung wurde unterbrochen.", exception);
        }
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsPhrase(String text, String phrase) {
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(phrase) + "(?![\\p{L}])")
                .matcher(text)
                .find();
    }
}
