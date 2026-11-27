package de.noizycologne.backend.instagram.gates;

import de.noizycologne.backend.instagram.store.PublishJob;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Liest die maßgeblichen Fakten ausschließlich aus der Datenbank. Es werden keine
 * Werte aus HTTP-Requests übernommen; unbekannte oder widersprüchliche Fakten
 * führen zu blockierenden Gates.
 */
@Repository
public class PublishFactReader {
    private record DraftFacts(
            String draftId,
            String draftStatus,
            boolean approvalNeedsReview,
            @Nullable String caption,
            @Nullable String hashtags,
            String calendarEntryId,
            @Nullable String eventStatus,
            @Nullable LocalDate eventDate,
            @Nullable LocalTime startTime,
            @Nullable String venueId,
            @Nullable String venueInstagram,
            @Nullable String partyId,
            @Nullable String eventFlyerId,
            @Nullable String defaultFlyerId) { }

    private record MediaFacts(String mediaType, long fileSize, @Nullable String checksum) { }

    private static final @NonNull RowMapper<@Nullable DraftFacts> DRAFT_ROW = (result, row) -> new DraftFacts(
            Objects.requireNonNull(result.getString("draft_id")),
            Objects.requireNonNull(result.getString("draft_status")),
            result.getBoolean("approval_needs_review"),
            result.getString("caption"),
            result.getString("hashtags"),
            Objects.requireNonNull(result.getString("calendar_entry_id")),
            result.getString("event_status"),
            date(result.getDate("event_date")),
            time(result.getTime("start_time")),
            result.getString("venue_id"),
            result.getString("venue_instagram"),
            result.getString("party_id"),
            result.getString("event_flyer_media_id"),
            result.getString("default_flyer_media_id"));

    private static final @NonNull RowMapper<@Nullable MediaFacts> MEDIA_ROW = (result, row) -> new MediaFacts(
            Objects.requireNonNull(result.getString("media_type")),
            result.getLong("file_size"),
            result.getString("checksum_sha256"));

    private final JdbcTemplate jdbc;
    private final WorkflowGateApprovals approvals;

    public PublishFactReader(JdbcTemplate jdbc, WorkflowGateApprovals approvals) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    public PublishFacts load(PublishJob job) {
        List<@Nullable DraftFacts> drafts = jdbc.query("""
                SELECT d.id AS draft_id, d.status AS draft_status, d.approval_needs_review,
                       d.caption, d.hashtags, c.id AS calendar_entry_id, c.event_status,
                       c.event_date, c.start_time, c.location_id AS venue_id,
                       c.party_id, c.event_flyer_media_id, p.default_flyer_media_id,
                       v.instagram AS venue_instagram
                  FROM editorial_post_drafts d
                  JOIN calendar_entries c ON c.id = d.calendar_entry_id
                  LEFT JOIN party_series p ON p.id = c.party_id
                  LEFT JOIN venues v ON v.id = c.location_id
                 WHERE d.id = ? AND c.id = ? AND d.calendar_entry_id = c.id
                """, DRAFT_ROW, job.draftId(), job.calendarEntryId());
        if (drafts.size() != 1) {
            return PublishFacts.EMPTY;
        }
        DraftFacts draft = Objects.requireNonNull(drafts.get(0), "draft facts");
        @Nullable String mediaId = draft.eventFlyerId() != null
                ? draft.eventFlyerId() : draft.defaultFlyerId();
        boolean mediaValid = validJpeg(mediaId);

        String entryId = draft.calendarEntryId();
        boolean cancellation = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM event_cancellations WHERE calendar_entry_id = ?)
                """, Boolean.class, entryId));
        boolean blockingChanges = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM event_changes WHERE calendar_entry_id = ? AND blocks_publication)
                    OR EXISTS(SELECT 1 FROM calendar_entries WHERE id = ? AND event_status = 'PLANNED')
                """, Boolean.class, entryId, entryId));

        int unresolvedMentions = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM editorial_post_draft_mentions m
                 WHERE m.draft_id = ?
                   AND NOT EXISTS (SELECT 1 FROM calendar_entry_mentions cm
                                    WHERE cm.entry_id = ? AND cm.actor_id = m.actor_id)
                """, Integer.class, draft.draftId(), entryId));
        int missingHandles = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM editorial_post_draft_mentions m
                  JOIN actors a ON a.id = m.actor_id
                 WHERE m.draft_id = ? AND (a.instagram IS NULL OR btrim(a.instagram) = '')
                """, Integer.class, draft.draftId()));

        int duplicatePositions = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM publishing.instagram_publish_job
                 WHERE account_id = ? AND position_in_plan = ? AND id <> ?
                   AND status <> 'CANCELLED_LOCALLY'
                """, Integer.class, job.accountId(), job.positionInPlan(), job.id()));
        List<@Nullable String> recentVenues = jdbc.query("""
                SELECT venue_id FROM publishing.instagram_publish_job
                 WHERE account_id = ? AND status = 'PUBLISHED' AND id <> ? AND planned_at <= ?
                 ORDER BY planned_at DESC, id DESC
                 LIMIT 3
                """, (result, row) -> result.getString(1),
                job.accountId(), job.id(), Timestamp.from(job.plannedAt()));

        @Nullable String jobVenueId = job.venueId();
        boolean lineupUnambiguous = unresolvedMentions == 0 && missingHandles == 0;
        boolean handlesFromMasterData = hasText(draft.venueInstagram()) && missingHandles == 0;
        boolean positionUnique = job.positionInPlan() > 0 && duplicatePositions == 0;
        boolean venueFeedDiversity = jobVenueId != null && !jobVenueId.isBlank()
                && recentVenues.stream().noneMatch(venue -> jobVenueId.equals(venue));

        return new PublishFacts(draft.draftId(), draft.draftStatus(), draft.approvalNeedsReview(),
                draft.caption(), draft.hashtags(), entryId, draft.eventStatus(), draft.eventDate(),
                draft.startTime(), draft.venueId(), draft.venueInstagram(), draft.partyId(),
                mediaId, mediaValid, blockingChanges, cancellation, lineupUnambiguous,
                handlesFromMasterData, positionUnique, venueFeedDiversity,
                job.positionInPlan(), job.planRevision(), approvals);
    }

    private boolean validJpeg(@Nullable String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return false;
        }
        String id = mediaId;
        List<@Nullable MediaFacts> media = jdbc.query("""
                SELECT media_type, file_size, checksum_sha256 FROM media_assets WHERE id = ?
                """, MEDIA_ROW, id);
        if (media.size() != 1) {
            return false;
        }
        MediaFacts facts = Objects.requireNonNull(media.get(0), "media facts");
        return "image/jpeg".equals(facts.mediaType())
                && facts.fileSize() > 0
                && hasText(facts.checksum());
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static @Nullable LocalDate date(@Nullable Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static @Nullable LocalTime time(@Nullable Time value) {
        return value == null ? null : value.toLocalTime();
    }
}
