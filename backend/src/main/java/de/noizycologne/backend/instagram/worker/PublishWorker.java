package de.noizycologne.backend.instagram.worker;

import de.noizycologne.backend.instagram.store.PublishJob;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Scheduleder Worker. Die Bean existiert nur, wenn sowohl der Worker-Flag als
 * auch {@code NOIZY_PUBLISH_ENABLED} ausdrücklich {@code true} sind. Ohne diese
 * Flags gibt es keine automatische Verarbeitung und keinen HTTP-Aufruf.
 */
public final class PublishWorker {
    private final String owner = "publish-worker-" + UUID.randomUUID();
    private final PublishJobStore store;
    private final PublishJobProcessor processor;
    private final ReconciliationService reconciliation;
    private final Duration lease;
    private final int batchSize;

    public PublishWorker(PublishJobStore store, PublishJobProcessor processor,
                         ReconciliationService reconciliation, Duration lease, int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${noizy.instagram.worker.interval-ms:60000}")
    public void runOnce() {
        List<PublishJob> claimed = store.claimDueJobs(owner, Instant.now(), lease, batchSize);
        for (PublishJob job : claimed) {
            processor.process(job);
        }
        reconciliation.reconcileUnknownJobs();
    }
}
