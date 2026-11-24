package de.noizycologne.backend.instagram;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Ergebnis eines einzelnen, expliziten Publish-Versuchs. Audio und Stories werden
 * bewusst nie als erledigt oder verifiziert ausgegeben.
 */
public record PublishAttempt(
        PublicationStatus status,
        @Nullable String mediaId,
        @Nullable String permalink,
        @Nullable String reason) {

    public PublishAttempt {
        status = Objects.requireNonNull(status, "status");
    }

    public static PublishAttempt blocked(String reason) {
        return new PublishAttempt(PublicationStatus.BLOCKED, null, null, reason);
    }

    public static PublishAttempt published(PublishResult result) {
        return new PublishAttempt(PublicationStatus.PUBLISHED, result.mediaId(), result.permalink(), null);
    }

    public static PublishAttempt unknownRemoteResult() {
        return new PublishAttempt(PublicationStatus.UNKNOWN_REMOTE_RESULT, null, null,
                "Timeout: Remote-Zustand unbekannt; kein erneuter Publish-Aufruf. Reconciliation erforderlich (SOLL).");
    }

    /** Audio bleibt trotz veröffentlichtem Feed-Post nicht verifizierbar. */
    public AudioStatus audioStatus() {
        return AudioStatus.NOT_VERIFIABLE;
    }
}
