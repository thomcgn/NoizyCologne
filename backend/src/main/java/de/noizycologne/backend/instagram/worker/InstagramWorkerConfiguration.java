package de.noizycologne.backend.instagram.worker;

import de.noizycologne.backend.instagram.store.PublishJobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Opt-in für den automatischen Worker; der Publishing-Flag bleibt zusätzlich zwingend. */
@Configuration
@ConditionalOnProperty(name = "noizy.instagram.worker.enabled", havingValue = "true")
public class InstagramWorkerConfiguration {
    @Bean
    @ConditionalOnProperty(name = "noizy.publish.enabled", havingValue = "true")
    PublishWorker publishWorker(PublishJobStore store, PublishJobProcessor processor,
                                ReconciliationService reconciliation,
                                @Value("${noizy.instagram.worker.lease-ms:300000}") long leaseMs,
                                @Value("${noizy.instagram.worker.batch-size:5}") int batchSize) {
        return new PublishWorker(store, processor, reconciliation,
                Duration.ofMillis(Math.max(1, leaseMs)), batchSize);
    }
}
