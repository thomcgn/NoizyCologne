package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.gates.PublishFacts;
import de.noizycologne.backend.instagram.gates.WorkflowGateApprovals;
import de.noizycologne.backend.instagram.store.PublishJob;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

final class PublishTestFixtures {
    private PublishTestFixtures() { }

    static PublishJob claimedJob() {
        return new PublishJob("job-1", "draft-1", "entry-1", "account-1", "venue-1",
                PublicationStatus.CLAIMED, Instant.parse("2027-01-01T10:00:00Z"), "key-1",
                "Freigegebene Caption", 1, 1, "worker-1",
                Instant.parse("2027-01-01T10:05:00Z"), 1, null, null, null, null,
                false, Instant.parse("2027-01-01T10:00:00Z"), Instant.parse("2027-01-01T10:00:00Z"));
    }

    static PublishJob unknownJobWithMedia() {
        PublishJob job = claimedJob();
        return new PublishJob(job.id(), job.draftId(), job.calendarEntryId(), job.accountId(),
                job.venueId(), PublicationStatus.UNKNOWN_REMOTE_RESULT, job.plannedAt(),
                job.idempotencyKey(), job.caption(), job.positionInPlan(), job.planRevision(),
                null, null, job.attemptCount(), job.containerId(), job.lastError(),
                "media-1", null, true, job.createdAt(), job.updatedAt());
    }

    static PublishFacts approvedFacts() {
        return new PublishFacts("draft-1", "APPROVED", false, "Freigegebene Caption", "#noizycologne",
                "entry-1", "ACTIVE", LocalDate.of(2027, 1, 9), LocalTime.of(22, 0),
                "venue-1", "venue_handle", "party-1", "media-1", true,
                false, false, true, true, true, true, 1, 1, WorkflowGateApprovals.ALL);
    }
}
