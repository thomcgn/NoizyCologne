package de.noizycologne.backend.genre;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenreAliasRequest(@NotBlank @Size(max = 100) String value) { }
