package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.gateway.FailClosedInstagramGateway;
import de.noizycologne.backend.instagram.store.PublishJob;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import de.noizycologne.backend.instagram.worker.PublishJobProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Läuft ausschließlich gegen die von CI bereitgestellte, isolierte Testdatenbank
 * (DB_URL/DB_USER/DB_PASSWORD aus check-quality.sh), niemals gegen Entwicklungs-
 * oder Produktionsdaten. Die Flags bleiben aus: Es findet kein HTTP-Aufruf statt.
 */
@SpringBootTest(properties = {
        "noizy.publish.enabled=false",
        "noizy.instagram.worker.enabled=false",
        "noizy.instagram.gateway.enabled=false",
        "noizy.discovery.enabled=false",
        "noizy.discovery.monitor.enabled=false",
        "logging.level.root=WARN"
})
@ActiveProfiles("local")
class InstagramPublishIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublishJobStore store;
    @Autowired PublishJobProcessor processor;
    @Autowired InstagramGateway gateway;

    private String venueId;
    private String partyId;
    private String mediaId;
    private String entryId;
    private String draftId;
    private final String accountId = "test-account-" + UUID.randomUUID();
    private final String idempotencyKey = "publish-key-" + UUID.randomUUID();

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString();
        venueId = "venue-" + suffix;
        partyId = "party-" + suffix;
        mediaId = "media-asset-" + suffix;
        entryId = "entry-" + suffix;
        draftId = "draft-" + suffix;
        jdbc.update("INSERT INTO venues(id,name,instagram) VALUES (?,?,'venue_handle')",
                venueId, "Publish Fixture " + suffix);
        jdbc.update("INSERT INTO party_series(id,name,color) VALUES (?,?,'#E11D48')",
                partyId, "Publish Party " + suffix);
        jdbc.update("""
                INSERT INTO media_assets(id,storage_path,original_filename,media_type,file_size,checksum_sha256)
                VALUES (?,?,?,'image/jpeg',1024,?)
                """, mediaId, "fixtures/" + suffix + ".jpg", "flyer.jpg", "a".repeat(64));
        jdbc.update("""
                INSERT INTO calendar_entries(id,event_date,location_id,entry_type,party_id,start_time)
                VALUES (?,?,?,'event',?,TIME '22:00')
                """, entryId, java.sql.Date.valueOf(LocalDate.of(2027, 1, 9)), venueId, partyId);
        jdbc.update("""
                INSERT INTO editorial_post_drafts(id,calendar_entry_id,caption,hashtags,status,approval_needs_review)
                VALUES (?,?,'Freigegebene Caption','#noizycologne','APPROVED',false)
                """, draftId, entryId);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM publishing.instagram_remote_publication WHERE account_id=?", accountId);
        jdbc.update("DELETE FROM publishing.instagram_publish_job WHERE account_id=?", accountId);
        jdbc.update("DELETE FROM editorial_post_drafts WHERE id=?", draftId);
        jdbc.update("DELETE FROM calendar_entries WHERE id=?", entryId);
        jdbc.update("DELETE FROM party_series WHERE id=?", partyId);
        jdbc.update("DELETE FROM media_assets WHERE id=?", mediaId);
        jdbc.update("DELETE FROM venues WHERE id=?", venueId);
    }

    @Test void duplicateEnqueueCreatesOneJobAndBlockedLifecycleIsPersisted() {
        assertThat(gateway).isInstanceOf(FailClosedInstagramGateway.class);
        Instant plannedAt = Instant.now().minusSeconds(10);
        String first = store.enqueue(idempotencyKey, draftId, entryId, accountId, venueId,
                plannedAt, "Freigegebene Caption", 1, 1);
        String second = store.enqueue(idempotencyKey, draftId, entryId, accountId, venueId,
                plannedAt.plusSeconds(60), "Geänderte Caption", 2, 2);
        String third = store.enqueue("other-key-" + UUID.randomUUID(), draftId, entryId,
                accountId, venueId, plannedAt, "Freigegebene Caption", 1, 1);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(jobCount()).isEqualTo(1);

        List<PublishJob> claimed = store.claimDueJobs("integration-worker", Instant.now(),
                java.time.Duration.ofMinutes(5), 5);
        assertThat(claimed).extracting(job -> Objects.requireNonNull(job).id()).containsExactly(first);
        assertThat(claimed.get(0).status()).isEqualTo(PublicationStatus.CLAIMED);
        assertThat(claimed.get(0).leaseOwner()).isEqualTo("integration-worker");
        assertThat(store.claimDueJobs("other-worker", Instant.now(),
                java.time.Duration.ofMinutes(5), 5)).isEmpty();

        PublishAttempt attempt = processor.process(claimed.get(0));

        assertThat(attempt.status()).isEqualTo(PublicationStatus.BLOCKED);
        assertThat(attempt.reason()).contains("NOIZY_PUBLISH_ENABLED");
        assertThat(attempt.audioStatus()).isEqualTo(AudioStatus.NOT_VERIFIABLE);
        PublishJob stored = Objects.requireNonNull(store.findById(first));
        assertThat(stored.status()).isEqualTo(PublicationStatus.BLOCKED);
        assertThat(stored.leaseOwner()).isNull();
        assertThat(stored.leaseUntil()).isNull();
        assertThat(store.gateResults(first)).hasSize(ConfiguredPublishEligibility.REQUIRED_GATES.size());
        assertThat(store.gateResults(first)).anyMatch(result -> !result.passed());
        assertThat(store.gateResults(first)).anySatisfy(result -> {
            assertThat(result.key()).isEqualTo("ADDITIONAL_WORKFLOW_DEFINED");
            assertThat(result.passed()).isFalse();
        });
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test void successfulOutcomePersistsMediaIdPermalinkAttemptAndRemoteObservation() {
        String jobId = store.enqueue(idempotencyKey, draftId, entryId, accountId, venueId,
                Instant.now().minusSeconds(10), "Freigegebene Caption", 1, 1);
        PublishJob job = store.claimDueJobs("integration-worker", Instant.now(),
                java.time.Duration.ofMinutes(5), 5).get(0);
        store.markContainerCreated(jobId, "container-1");
        store.markReadyForPublish(jobId);
        store.markPublishing(jobId);

        store.completePublished(job, new PublishResult("media-remote-1",
                "https://instagram.example/p/media-remote-1"));

        PublishJob stored = Objects.requireNonNull(store.findById(jobId));
        assertThat(stored.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(stored.mediaId()).isEqualTo("media-remote-1");
        assertThat(stored.permalink()).isEqualTo("https://instagram.example/p/media-remote-1");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM publishing.instagram_publish_attempt WHERE job_id=? AND status='PUBLISHED'
                """, Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM publishing.instagram_remote_publication WHERE media_id='media-remote-1'
                """, Integer.class)).isEqualTo(1);
    }

    @Test void timeoutMarksJobForReconciliationWithoutRemotePublication() {
        String jobId = store.enqueue(idempotencyKey, draftId, entryId, accountId, venueId,
                Instant.now().minusSeconds(10), "Freigegebene Caption", 1, 1);
        PublishJob job = store.claimDueJobs("integration-worker", Instant.now(),
                java.time.Duration.ofMinutes(5), 5).get(0);
        store.markContainerCreated(jobId, "container-1");
        store.markReadyForPublish(jobId);
        store.markPublishing(jobId);

        store.completeUnknown(job, "simulierter Timeout");

        PublishJob stored = Objects.requireNonNull(store.findById(jobId));
        assertThat(stored.status()).isEqualTo(PublicationStatus.UNKNOWN_REMOTE_RESULT);
        assertThat(stored.needsReconciliation()).isTrue();
        assertThat(stored.mediaId()).isNull();
        assertThat(store.findJobsNeedingReconciliation(10))
                .extracting(entry -> Objects.requireNonNull(entry).id()).contains(jobId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM publishing.instagram_publish_attempt
                 WHERE job_id=? AND status='UNKNOWN_REMOTE_RESULT'
                """, Integer.class, jobId)).isEqualTo(1);
    }

    private long jobCount() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM publishing.instagram_publish_job WHERE account_id=?
                """, Long.class, accountId);
        return count == null ? 0L : count;
    }
}
