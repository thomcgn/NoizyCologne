package de.noizycologne.backend.instagram.store;

import de.noizycologne.backend.instagram.GateResult;
import de.noizycologne.backend.instagram.PublicationStatus;
import de.noizycologne.backend.instagram.PublishResult;
import de.noizycologne.backend.instagram.RemotePublication;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistenz der Publishing-Warteschlange mit explizitem Sperr-SQL.
 *
 * <p>Alle Eingaben stammen aus serverseitigem Code. Insbesondere werden Gate-
 * Ergebnisse nie aus HTTP-Parametern übernommen. claims verwenden
 * {@code FOR UPDATE SKIP LOCKED} und eine Lease, damit parallele Worker keinen
 * doppelten {@code media_publish}-Aufruf auslösen.</p>
 */
@Repository
public class PublishJobStore {
    private static final @NonNull RowMapper<@Nullable PublishJob> ROW = (result, row) -> new PublishJob(
            required(result, "id"),
            required(result, "draft_id"),
            required(result, "calendar_entry_id"),
            required(result, "account_id"),
            result.getString("venue_id"),
            PublicationStatus.valueOf(required(result, "status")),
            Objects.requireNonNull(instant(result, "planned_at")),
            required(result, "idempotency_key"),
            required(result, "caption"),
            result.getInt("position_in_plan"),
            result.getLong("plan_revision"),
            result.getString("lease_owner"),
            instant(result, "lease_until"),
            result.getInt("attempt_count"),
            result.getString("container_id"),
            result.getString("last_error"),
            result.getString("media_id"),
            result.getString("permalink"),
            result.getBoolean("needs_reconciliation"),
            Objects.requireNonNull(instant(result, "created_at")),
            Objects.requireNonNull(instant(result, "updated_at")));

    private final JdbcTemplate jdbc;

    public PublishJobStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Liefert bei gleicher Idempotency-Key oder gleichem Draft/Konto immer denselben Job. */
    @Transactional
    public String enqueue(String idempotencyKey, String draftId, String calendarEntryId,
                          String accountId, @Nullable String venueId, Instant plannedAt,
                          String caption, int positionInPlan, long planRevision) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(calendarEntryId, "calendarEntryId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(plannedAt, "plannedAt");
        Objects.requireNonNull(caption, "caption");
        String newId = UUID.randomUUID().toString();
        return Objects.requireNonNull(jdbc.queryForObject("""
                WITH candidate AS (
                    INSERT INTO publishing.instagram_publish_job
                        (id, draft_id, calendar_entry_id, account_id, venue_id, status,
                         planned_at, idempotency_key, caption, position_in_plan, plan_revision)
                    VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                )
                SELECT id FROM candidate
                UNION ALL
                SELECT id FROM publishing.instagram_publish_job
                 WHERE idempotency_key = ? OR (draft_id = ? AND account_id = ?)
                LIMIT 1
                """, String.class,
                newId, draftId, calendarEntryId, accountId, venueId, Timestamp.from(plannedAt),
                idempotencyKey, caption, positionInPlan, planRevision,
                idempotencyKey, draftId, accountId));
    }

    public @Nullable PublishJob findById(String jobId) {
        return jdbc.query("SELECT * FROM publishing.instagram_publish_job WHERE id = ?", ROW, jobId)
                .stream().findFirst().orElse(null);
    }

    /** Sperrt fällige Jobs exklusiv und übernimmt sie mit einer Lease. */
    @Transactional
    public List<PublishJob> claimDueJobs(String owner, Instant now, Duration lease, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) throw new IllegalArgumentException("limit muss positiv sein");
        Timestamp nowValue = Timestamp.from(now);
        Timestamp leaseUntil = Timestamp.from(now.plus(lease));
        List<String> ids = jdbc.query("""
                SELECT id FROM publishing.instagram_publish_job
                 WHERE status = 'SCHEDULED' AND planned_at <= ?
                   AND (lease_until IS NULL OR lease_until <= ?)
                 ORDER BY planned_at, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (result, row) -> required(result, "id"), nowValue, nowValue, limit);
        List<PublishJob> claimed = new ArrayList<>();
        for (String id : ids) {
            List<@Nullable PublishJob> updated = jdbc.query("""
                    UPDATE publishing.instagram_publish_job
                       SET status = 'CLAIMED', lease_owner = ?, lease_until = ?,
                           attempt_count = attempt_count + 1, updated_at = now()
                     WHERE id = ? AND status = 'SCHEDULED'
                       AND (lease_until IS NULL OR lease_until <= ?)
                    RETURNING *
                    """, ROW, owner, leaseUntil, id, nowValue);
            @Nullable PublishJob first = updated.stream().findFirst().orElse(null);
            if (first != null) {
                claimed.add(first);
            }
        }
        return List.copyOf(claimed);
    }

    public List<PublishJob> findJobsNeedingReconciliation(int limit) {
        List<PublishJob> jobs = new ArrayList<>();
        List<@Nullable PublishJob> rows = jdbc.query("""
                SELECT * FROM publishing.instagram_publish_job
                 WHERE status = 'UNKNOWN_REMOTE_RESULT' AND needs_reconciliation
                 ORDER BY updated_at, id
                 LIMIT ?
                """, ROW, limit);
        for (@Nullable PublishJob job : rows) {
            if (job != null) {
                jobs.add(job);
            }
        }
        return List.copyOf(jobs);
    }

    /** Speichert ausschließlich serverseitig erzeugte Gate-Ergebnisse. */
    @Transactional
    public void recordGateResults(String jobId, Map<String, GateResult> results,
                                  Map<String, String> evidence) {
        Objects.requireNonNull(results, "results");
        for (GateResult result : results.values()) {
            jdbc.update("""
                    INSERT INTO publishing.instagram_publish_gate_result
                        (job_id, gate_key, passed, message, evidence, evaluated_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (job_id, gate_key) DO UPDATE
                       SET passed = EXCLUDED.passed, message = EXCLUDED.message,
                           evidence = EXCLUDED.evidence, evaluated_at = now()
                    """, jobId, result.key(), result.passed(), result.message(),
                    evidence.get(result.key()));
        }
    }

    public List<GateResult> gateResults(String jobId) {
        return jdbc.query("""
                SELECT gate_key, passed, message FROM publishing.instagram_publish_gate_result
                 WHERE job_id = ? ORDER BY gate_key
                """, (result, row) -> new GateResult(required(result, "gate_key"),
                result.getBoolean("passed"), required(result, "message")), jobId);
    }

    /** Statuswechsel nur aus dem erwarteten Zustand; unerwartete Zustände fail closed. */
    public void transition(String jobId, PublicationStatus from, PublicationStatus to,
                           @Nullable String error, boolean releaseLease) {
        String sql = releaseLease ? """
                UPDATE publishing.instagram_publish_job
                   SET status = ?, last_error = COALESCE(?, last_error),
                       lease_owner = NULL, lease_until = NULL, updated_at = now()
                 WHERE id = ? AND status = ?
                """ : """
                UPDATE publishing.instagram_publish_job
                   SET status = ?, last_error = COALESCE(?, last_error), updated_at = now()
                 WHERE id = ? AND status = ?
                """;
        requireOne(jdbc.update(sql, to.name(), error, jobId, from.name()), jobId, from, to);
    }

    public void markContainerCreated(String jobId, String containerId) {
        requireOne(jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET status = 'CONTAINER_CREATED', container_id = ?, updated_at = now()
                 WHERE id = ? AND status = 'CLAIMED'
                """, containerId, jobId), jobId, PublicationStatus.CLAIMED,
                PublicationStatus.CONTAINER_CREATED);
    }

    public void markReadyForPublish(String jobId) {
        transition(jobId, PublicationStatus.CONTAINER_CREATED,
                PublicationStatus.READY_FOR_PUBLISH, null, false);
    }

    public void markPublishing(String jobId) {
        transition(jobId, PublicationStatus.READY_FOR_PUBLISH,
                PublicationStatus.PUBLISHING, null, false);
    }

    /** Erfolgreicher, genau einmaliger publish: Status, IDs und Remote-Befund gemeinsam. */
    @Transactional
    public void completePublished(PublishJob job, PublishResult result) {
        transition(job.id(), PublicationStatus.PUBLISHING, PublicationStatus.PUBLISHED, null, true);
        jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET media_id = ?, permalink = ?, needs_reconciliation = false, updated_at = now()
                 WHERE id = ?
                """, result.mediaId(), result.permalink(), job.id());
        recordAttempt(job, PublicationStatus.PUBLISHED, null, result.mediaId(), result.permalink());
        observe(job.id(), job.accountId(), new RemotePublication(
                result.mediaId(), result.permalink(), null), "PUBLISH");
    }

    /**
     * Timeout der externen Grenze: nie erneut publish aufrufen, sondern nur zur
     * Reconciliation markieren. Der Remote-Zustand bleibt ausdrücklich unbekannt.
     */
    @Transactional
    public void completeUnknown(PublishJob job, String reason) {
        transition(job.id(), PublicationStatus.PUBLISHING,
                PublicationStatus.UNKNOWN_REMOTE_RESULT, reason, true);
        jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET needs_reconciliation = true, updated_at = now()
                 WHERE id = ?
                """, job.id());
        recordAttempt(job, PublicationStatus.UNKNOWN_REMOTE_RESULT, reason, null, null);
    }

    @Transactional
    public void completeFailed(PublishJob job, PublicationStatus from, String reason) {
        transition(job.id(), from, PublicationStatus.FAILED, reason, true);
        recordAttempt(job, PublicationStatus.FAILED, reason, null, null);
    }

    /** Lokales Stornieren ist nur vor dem eigentlichen publish-Aufruf erlaubt. */
    public boolean cancelLocally(String jobId) {
        int rows = jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET status = 'CANCELLED_LOCALLY', lease_owner = NULL, lease_until = NULL,
                       updated_at = now()
                 WHERE id = ? AND status IN
                     ('DRAFT','SCHEDULED','BLOCKED','CLAIMED','CONTAINER_CREATED','READY_FOR_PUBLISH')
                """, jobId);
        return rows == 1;
    }

    /** Bestätigt einen unbekannten Job ausschließlich aus einem lesenden Remote-Befund. */
    @Transactional
    public void confirmReconciled(PublishJob job, RemotePublication publication, String reason) {
        transition(job.id(), PublicationStatus.UNKNOWN_REMOTE_RESULT,
                PublicationStatus.PUBLISHED, reason, true);
        jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET media_id = ?, permalink = ?, needs_reconciliation = false, updated_at = now()
                 WHERE id = ?
                """, publication.mediaId(), publication.permalink(), job.id());
        observe(job.id(), job.accountId(), publication, "FETCH");
    }

    public void keepUnknown(String jobId, String reason) {
        jdbc.update("""
                UPDATE publishing.instagram_publish_job
                   SET last_error = ?, needs_reconciliation = true, updated_at = now()
                 WHERE id = ? AND status = 'UNKNOWN_REMOTE_RESULT'
                """, reason, jobId);
    }

    public void observe(String jobId, String accountId, RemotePublication publication, String source) {
        Objects.requireNonNull(publication, "publication");
        jdbc.update("""
                INSERT INTO publishing.instagram_remote_publication
                    (media_id, job_id, account_id, permalink, caption, observed_by, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (media_id) DO UPDATE
                   SET permalink = EXCLUDED.permalink, caption = EXCLUDED.caption,
                       observed_by = EXCLUDED.observed_by, observed_at = now()
                """, publication.mediaId(), jobId, accountId, publication.permalink(),
                publication.caption(), source);
    }

    private void recordAttempt(PublishJob job, PublicationStatus status, @Nullable String error,
                               @Nullable String mediaId, @Nullable String permalink) {
        jdbc.update("""
                INSERT INTO publishing.instagram_publish_attempt
                    (job_id, attempt_no, status, error, media_id, permalink, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (job_id, attempt_no) DO UPDATE
                   SET status = EXCLUDED.status, error = EXCLUDED.error,
                       media_id = EXCLUDED.media_id, permalink = EXCLUDED.permalink,
                       finished_at = now()
                """, job.id(), Math.max(1, job.attemptCount()), status.name(),
                error, mediaId, permalink);
    }

    private static void requireOne(int rows, String jobId, PublicationStatus from, PublicationStatus to) {
        if (rows != 1) {
            throw new IllegalStateException("Unerwarteter Statuswechsel " + from + " -> " + to
                    + " für Job " + jobId);
        }
    }

    private static String required(ResultSet result, String column) throws SQLException {
        return Objects.requireNonNull(result.getString(column), column);
    }

    private static @Nullable Instant instant(ResultSet result, String column) throws SQLException {
        @Nullable Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
