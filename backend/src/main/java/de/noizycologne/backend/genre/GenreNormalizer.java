package de.noizycologne.backend.genre;

import java.text.Normalizer;
import java.util.Locale;

/** The same token identity is used by CRUD, bulk imports and OCR matching. */
public final class GenreNormalizer {
    private GenreNormalizer() { }
    public static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("[‐‑‒–—−]", "-").replaceAll("[’'`´]", "'")
                .replace("&", " and ").replaceAll("[^\\p{L}\\p{N}']+", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
