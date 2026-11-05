package de.noizycologne.backend.media;

import org.jspecify.annotations.NonNull;
import java.util.List;

public record FlyerGenreAnalysisResponse(List<@NonNull GenreSuggestion> genres) {
    public FlyerGenreAnalysisResponse {
        genres = List.copyOf(genres);
    }

    public record GenreSuggestion(String genre, double confidence, boolean uncertain) { }
}
