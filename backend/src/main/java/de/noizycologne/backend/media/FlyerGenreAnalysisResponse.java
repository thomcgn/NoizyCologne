package de.noizycologne.backend.media;

import java.util.List;

public record FlyerGenreAnalysisResponse(List<GenreSuggestion> genres) {
    public FlyerGenreAnalysisResponse {
        genres = List.copyOf(genres);
    }

    public record GenreSuggestion(String genre, double confidence, boolean uncertain) { }
}
