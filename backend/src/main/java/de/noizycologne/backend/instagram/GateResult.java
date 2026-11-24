package de.noizycologne.backend.instagram;

/** Ergebnis eines einzelnen Pflicht-Gates (docs/PROJECT.md, 17.1). */
public record GateResult(String key, boolean passed, String message) {
}
