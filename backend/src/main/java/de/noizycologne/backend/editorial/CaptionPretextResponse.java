package de.noizycologne.backend.editorial;

/** Versionierte teamweite Caption-Konfiguration; die Revision schützt vor überschriebenen Änderungen. */
public record CaptionPretextResponse(String pretext, long revision) { }
