package de.noizycologne.backend.instagram;

import java.net.URI;
import java.util.Objects;

/**
 * Skizze für genau einen expliziten Publish-Versuch. Kein Scheduler, kein
 * Automatismus, keine HTTP-Implementierung. Der Aufruf geht ausschließlich über
 * das injizierte Gateway (in Tests das deterministische In-Memory-Gateway).
 */
public final class PublishCoordinator {
    private final PublishEligibilityPort eligibility;
    private final InstagramGateway gateway;

    public PublishCoordinator(PublishEligibilityPort eligibility, InstagramGateway gateway) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public PublishAttempt attempt(PublishContext context, InstagramAccount account,
                                  URI publicJpegUrl, String caption) {
        PublishDecision decision = eligibility.evaluate(context);
        if (!decision.allowed()) {
            return PublishAttempt.blocked(decision.reason());
        }
        MediaContainerResult container = gateway.createPhotoContainer(account, publicJpegUrl, caption);
        ContainerStatus status = gateway.fetchContainerStatus(account, container.containerId());
        if (!status.readyForPublish()) {
            return PublishAttempt.blocked("Container nicht bereit: " + status);
        }
        try {
            PublishResult result = gateway.publishContainer(account, container.containerId());
            return PublishAttempt.published(result);
        } catch (GatewayTimeoutException timeout) {
            // Genau ein Versuch: ein blindes Retry ohne Kenntnis des Remote-Zustands
            // würde einen Doppelpost erzeugen. Die Reconciliation über
            // fetchPublication/recentMedia ist Pflicht (SOLL, ADR 0001), aber bewusst
            // noch nicht implementiert – deshalb UNKNOWN_REMOTE_RESULT.
            return PublishAttempt.unknownRemoteResult();
        }
    }
}
