package de.noizycologne.backend.genre;

public record GenreAliasResponse(String id, String value) {
    public static GenreAliasResponse from(GenreAlias alias) {
        return new GenreAliasResponse(alias.getId(), alias.getValue());
    }
}
