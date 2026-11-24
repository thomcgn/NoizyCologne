package de.noizycologne.backend.instagram;

import org.jspecify.annotations.Nullable;

/** Lesender Remote-Befund für Reconciliation; kein Beweis für Audio oder Stories. */
public record RemotePublication(String mediaId, String permalink, @Nullable String caption) {
}
