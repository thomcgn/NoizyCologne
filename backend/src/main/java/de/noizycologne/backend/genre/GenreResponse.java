package de.noizycologne.backend.genre;

import java.util.List;

public record GenreResponse(String id, String canonicalName, String displayName, boolean active,
                            List<GenreAliasResponse> aliases) {
    public static GenreResponse from(Genre genre, List<GenreAliasResponse> aliases) {
        return new GenreResponse(genre.getId(), genre.getCanonicalName(), genre.getDisplayName(), genre.isActive(), aliases);
    }
}
