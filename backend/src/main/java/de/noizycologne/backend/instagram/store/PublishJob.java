package de.noizycologne.backend.instagram.store;

import de.noizycologne.backend.instagram.PublicationStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Serverseitiger Publishing-Job. Gate-Daten und Leases stammen ausschließlich aus
 * der Datenbank; clientgelieferte Freigaben gibt es bewusst nicht.
 */
public record PublishJob(
        String id,
        String draftId,
        String calendarEntryId,
        String accountId,
        @Nullable String venueId,
        PublicationStatus status,
        Instant plannedAt,
        String idempotencyKey,
        String caption,
        int positionInPlan,
        long planRevision,
        @Nullable String leaseOwner,
        @Nullable Instant leaseUntil,
        int attemptCount,
        @Nullable String containerId,
        @Nullable String lastError,
        @Nullable String mediaId,
        @Nullable String permalink,
        boolean needsReconciliation,
        Instant createdAt,
        Instant updatedAt) {
}
