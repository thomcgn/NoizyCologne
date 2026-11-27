package de.noizycologne.backend.instagram.gates;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Authentische, serverseitig gelesene Fakten für die Gate-Prüfung.
 * Fehlende Fakten sind {@code null} oder {@code false} und blockieren das
 * zugehörige Gate; es gibt keine Annahmen zugunsten eines Posts.
 */
public record PublishFacts(
        @Nullable String draftId,
        @Nullable String draftStatus,
        boolean approvalNeedsReview,
        @Nullable String caption,
        @Nullable String hashtags,
        @Nullable String calendarEntryId,
        @Nullable String eventStatus,
        @Nullable LocalDate eventDate,
        @Nullable LocalTime startTime,
        @Nullable String venueId,
        @Nullable String venueInstagram,
        @Nullable String partyId,
        @Nullable String mediaAssetId,
        boolean mediaValid,
        boolean blockingChanges,
        boolean cancellation,
        boolean lineupUnambiguous,
        boolean handlesFromMasterData,
        boolean positionUnique,
        boolean venueFeedDiversity,
        int positionInPlan,
        long planRevision,
        WorkflowGateApprovals approvals) {

    public static final PublishFacts EMPTY = new PublishFacts(
            null, null, true, null, null, null, null, null, null, null, null, null,
            null, false, true, true, false, false, false, false, 0, 0, WorkflowGateApprovals.NONE);

    /** Nur ein redaktionell freigegebener, nicht blockierter Draft ist publish-fähig. */
    public boolean draftApproved() {
        return "APPROVED".equals(draftStatus)
                && !approvalNeedsReview
                && !blockingChanges
                && !cancellation
                && !"PLANNED".equals(eventStatus);
    }
}
