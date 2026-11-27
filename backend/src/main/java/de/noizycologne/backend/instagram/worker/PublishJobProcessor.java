package de.noizycologne.backend.instagram.worker;

import de.noizycologne.backend.instagram.ContainerStatus;
import de.noizycologne.backend.instagram.GatewayTimeoutException;
import de.noizycologne.backend.instagram.InstagramAccount;
import de.noizycologne.backend.instagram.InstagramGateway;
import de.noizycologne.backend.instagram.MediaContainerResult;
import de.noizycologne.backend.instagram.PublishAttempt;
import de.noizycologne.backend.instagram.PublishDecision;
import de.noizycologne.backend.instagram.PublishEligibilityPort;
import de.noizycologne.backend.instagram.PublicationStatus;
import de.noizycologne.backend.instagram.PublishResult;
import de.noizycologne.backend.instagram.gates.GateEvaluation;
import de.noizycologne.backend.instagram.gates.PublishFacts;
import de.noizycologne.backend.instagram.gates.PublishFactReader;
import de.noizycologne.backend.instagram.gates.ServerSideGateEvaluator;
import de.noizycologne.backend.instagram.store.PublishJob;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Führt einen bereits exklusiv geclaimten Job aus. Die Freigabe entsteht
 * ausschließlich aus Datenbankfakten und dem zwingend aufzurufenden
 * {@link PublishEligibilityPort}; ein Aufruf von {@code publishContainer}
 * erfolgt höchstens einmal pro Job und Versuch.
 */
@Service
public class PublishJobProcessor {
    private final PublishJobStore store;
    private final PublishFactReader factReader;
    private final ServerSideGateEvaluator evaluator;
    private final PublishEligibilityPort eligibility;
    private final InstagramGateway gateway;
    private final @Nullable String mediaPublicBaseUrl;
    private final int maxContainerPolls;
    private final Duration pollDelay;

    public PublishJobProcessor(PublishJobStore store, PublishFactReader factReader,
                               ServerSideGateEvaluator evaluator, PublishEligibilityPort eligibility,
                               InstagramGateway gateway,
                               @Value("${noizy.instagram.media-public-base-url:}") @Nullable String mediaPublicBaseUrl,
                               @Value("${noizy.instagram.gateway.max-container-polls:10}") int maxContainerPolls,
                               @Value("${noizy.instagram.worker.poll-delay-ms:1000}") long pollDelayMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.factReader = Objects.requireNonNull(factReader, "factReader");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.mediaPublicBaseUrl = mediaPublicBaseUrl;
        this.maxContainerPolls = Math.max(1, maxContainerPolls);
        this.pollDelay = Duration.ofMillis(Math.max(0, pollDelayMs));
    }

    public PublishAttempt process(PublishJob job) {
        Objects.requireNonNull(job, "job");
        PublishFacts facts = factReader.load(job);
        GateEvaluation evaluation = evaluator.evaluate(facts);
        store.recordGateResults(job.id(), evaluation.gateResults(), evaluation.evidence());
        PublishDecision decision = eligibility.evaluate(evaluation.context());
        if (!decision.allowed()) {
            store.transition(job.id(), PublicationStatus.CLAIMED,
                    PublicationStatus.BLOCKED, decision.reason(), true);
            return PublishAttempt.blocked(decision.reason());
        }

        @Nullable String base = mediaPublicBaseUrl;
        @Nullable String assetId = facts.mediaAssetId();
        if (!publicHttpsUrl(base) || assetId == null) {
            return failBeforeContainer(job, PublicationStatus.CLAIMED,
                    "Keine geprüfte öffentliche Bild-URL konfiguriert");
        }
        URI image = URI.create(trimTrailingSlash(base) + "/" + assetId + ".jpg");
        String caption = caption(facts);
        InstagramAccount account = new InstagramAccount(job.accountId());

        @Nullable String containerId = null;
        try {
            MediaContainerResult container = gateway.createPhotoContainer(account, image, caption);
            containerId = container.containerId();
            store.markContainerCreated(job.id(), containerId);
            ContainerStatus status = pollContainer(account, containerId);
            if (!status.readyForPublish()) {
                return failBeforeContainer(job, PublicationStatus.CONTAINER_CREATED,
                        "Container nicht veröffentlichungsbereit: " + status);
            }
            store.markReadyForPublish(job.id());
            store.markPublishing(job.id());
        } catch (GatewayTimeoutException timeout) {
            // Kein publish-Aufruf erfolgt: kein unbekannter Feed-Post, kein Retry.
            return failBeforeContainer(job, PublicationStatus.CLAIMED,
                    "Timeout vor media_publish; kein Publish-Aufruf");
        } catch (RuntimeException error) {
            PublicationStatus from = containerId == null
                    ? PublicationStatus.CLAIMED : PublicationStatus.CONTAINER_CREATED;
            return failBeforeContainer(job, from,
                    "Container-Fehler: " + error.getClass().getSimpleName());
        }

        String id = Objects.requireNonNull(containerId, "containerId");
        try {
            PublishResult result = gateway.publishContainer(account, id);
            store.completePublished(job, result);
            return PublishAttempt.published(result);
        } catch (GatewayTimeoutException timeout) {
            // Genau ein Versuch. Ein blindes Retry würde einen Doppelpost erzeugen;
            // nur lesende Reconciliation darf den Zustand klären.
            store.completeUnknown(job, "Timeout bei media_publish: Remote-Zustand unbekannt, kein Retry");
            return PublishAttempt.unknownRemoteResult();
        } catch (RuntimeException error) {
            String reason = "Publish-Fehler: " + error.getClass().getSimpleName();
            store.completeFailed(job, PublicationStatus.PUBLISHING, reason);
            return new PublishAttempt(PublicationStatus.FAILED, null, null, reason);
        }
    }

    private PublishAttempt failBeforeContainer(PublishJob job, PublicationStatus from, String reason) {
        store.completeFailed(job, from, reason);
        return new PublishAttempt(PublicationStatus.FAILED, null, null, reason);
    }

    private ContainerStatus pollContainer(InstagramAccount account, String containerId) {
        ContainerStatus last = ContainerStatus.IN_PROGRESS;
        for (int attempt = 0; attempt < maxContainerPolls; attempt++) {
            last = gateway.fetchContainerStatus(account, containerId);
            if (last.readyForPublish() || last == ContainerStatus.FAILED || last == ContainerStatus.EXPIRED) {
                return last;
            }
            if (attempt + 1 < maxContainerPolls && !pollDelay.isZero()) {
                try {
                    Thread.sleep(pollDelay.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return ContainerStatus.FAILED;
                }
            }
        }
        return last;
    }

    private static String caption(PublishFacts facts) {
        @Nullable String caption = facts.caption();
        @Nullable String hashtags = facts.hashtags();
        String text = caption == null ? "" : caption.trim();
        String tags = hashtags == null ? "" : hashtags.trim();
        return tags.isEmpty() ? text : text + "\n\n" + tags;
    }

    private static boolean publicHttpsUrl(@Nullable String base) {
        if (base == null || base.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(base);
            return "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
