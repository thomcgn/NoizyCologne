package de.noizycologne.backend.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpenAiFlyerGenreAnalyzer implements FlyerGenreAnalyzer {

    private static final Set<String> ALLOWED_GENRES = Set.of(
            "Rock", "Alternative", "Indie", "Punk", "Punkrock", "Garage Rock",
            "Rock’n’Roll", "Emo", "Hardcore", "Metal", "Metalcore", "Heavy Metal",
            "Thrash Metal", "Death Metal", "Black Metal", "Doom", "Hard Rock",
            "Psych", "Ska", "Soul", "Post-Punk", "Darkwave", "Gothic", "Britpop",
            "Singer-Songwriter", "Folk", "Pop", "Sonstiges"
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public OpenAiFlyerGenreAnalyzer(ObjectMapper objectMapper,
                                    @Value("${noizy.ai.openai.api-key:}") String apiKey,
                                    @Value("${noizy.ai.openai.model:gpt-4.1-mini}") String model,
                                    @Value("${noizy.ai.openai.url:https://api.openai.com/v1/chat/completions}") String apiUrl) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
    }

    @Override
    public FlyerGenreAnalysisResponse analyze(MediaAsset asset, Path imagePath) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Die Flyer-Genreanalyse ist lokal nicht konfiguriert (OPENAI_API_KEY fehlt)." );
        }

        try {
            String imageData = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", 300,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt()),
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", "Analysiere diesen Flyer. Liefere nur Genre-Vorschläge aus der erlaubten Liste."),
                                    Map.of("type", "image_url", "image_url", Map.of(
                                            "url", "data:" + asset.getMediaType() + ";base64," + imageData,
                                            "detail", "low"
                                    ))
                            ))
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Die Flyer-Genreanalyse konnte beim KI-Dienst nicht ausgeführt werden.");
            }
            return parseResponse(response.body());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Die Flyer-Genreanalyse wurde unterbrochen.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Die Antwort der Flyer-Genreanalyse konnte nicht verarbeitet werden.", exception);
        }
    }

    private FlyerGenreAnalysisResponse parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        JsonNode genres = objectMapper.readTree(content).path("genres");
        if (!genres.isArray()) {
            throw new IOException("KI-Antwort enthält keine Genre-Liste");
        }

        Map<String, FlyerGenreAnalysisResponse.GenreSuggestion> unique = new LinkedHashMap<>();
        for (JsonNode suggestion : genres) {
            String genre = suggestion.path("genre").asText("");
            double confidence = suggestion.path("confidence").asDouble(-1);
            if (ALLOWED_GENRES.contains(genre) && confidence >= 0 && confidence <= 1) {
                unique.putIfAbsent(genre, new FlyerGenreAnalysisResponse.GenreSuggestion(genre, confidence));
            }
        }
        return new FlyerGenreAnalysisResponse(new ArrayList<>(unique.values()));
    }

    private String systemPrompt() {
        return "Du analysierst Veranstaltungsflyer. Erkenne nur musikalische Genres, die auf dem Flyer "
                + "explizit genannt oder durch eindeutig erkennbare Acts/Veranstaltungsbezeichnungen plausibel sind. "
                + "Nutze ausschließlich diese Genrebezeichnungen: " + String.join(", ", ALLOWED_GENRES) + ". "
                + "Antworte ausschließlich als JSON mit dem Format {\"genres\":[{\"genre\":\"Rock\",\"confidence\":0.0}]}. "
                + "confidence liegt zwischen 0 und 1. Bei Unsicherheit gib keine Vermutung aus.";
    }
}
