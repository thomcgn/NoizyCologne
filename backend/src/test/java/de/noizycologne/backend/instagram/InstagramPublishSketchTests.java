package de.noizycologne.backend.instagram;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramPublishSketchTests {
    private static final InstagramAccount ACCOUNT = new InstagramAccount("test-account");
    private static final URI IMAGE = URI.create("https://example.invalid/flyer.jpg");

    private static Map<String, GateResult> allGatesPassed() {
        Map<String, GateResult> gates = new LinkedHashMap<>();
        for (String key : ConfiguredPublishEligibility.REQUIRED_GATES) {
            gates.put(key, new GateResult(key, true, "geprüft"));
        }
        return gates;
    }

    private static PublishContext approvedContext() {
        return PublishContext.approvedWith(allGatesPassed());
    }

    @Test
    void flagFalseDeniesPublishEvenWhenDraftIsFormallyApproved() {
        PublishCoordinator coordinator = new PublishCoordinator(
                new ConfiguredPublishEligibility("false"), new InMemoryInstagramGateway());

        PublishAttempt attempt = coordinator.attempt(approvedContext(), ACCOUNT, IMAGE, "Caption");

        assertThat(attempt.status()).isEqualTo(PublicationStatus.BLOCKED);
        assertThat(attempt.reason()).contains("NOIZY_PUBLISH_ENABLED");
        assertThat(attempt.audioStatus()).isEqualTo(AudioStatus.NOT_VERIFIABLE);
    }

    @Test
    void missingOrInvalidFlagFailsClosed() {
        assertThat(ConfiguredPublishEligibility.parseFlag(null)).isFalse();
        assertThat(ConfiguredPublishEligibility.parseFlag("")).isFalse();
        assertThat(ConfiguredPublishEligibility.parseFlag("false")).isFalse();
        assertThat(ConfiguredPublishEligibility.parseFlag("maybe")).isFalse();
        assertThat(ConfiguredPublishEligibility.parseFlag("TrUe")).isTrue();
    }

    @Test
    void mockGatewayReturnsDeterministicContainerAndPublishResults() {
        InMemoryInstagramGateway gateway = new InMemoryInstagramGateway();
        PublishCoordinator coordinator = new PublishCoordinator(
                new ConfiguredPublishEligibility("true"), gateway);

        PublishAttempt first = coordinator.attempt(approvedContext(), ACCOUNT, IMAGE, "Caption A");
        PublishAttempt second = coordinator.attempt(approvedContext(), ACCOUNT, IMAGE, "Caption B");

        assertThat(first.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(first.mediaId()).isEqualTo("media-1");
        assertThat(first.permalink()).isEqualTo("https://www.instagram.com/p/media-1/");
        assertThat(second.mediaId()).isEqualTo("media-2");
        assertThat(gateway.createCalls()).isEqualTo(2);
        assertThat(gateway.statusCalls()).isEqualTo(2);
        assertThat(gateway.publishCalls()).isEqualTo(2);
        assertThat(gateway.fetchPublication(ACCOUNT, "media-1")).isPresent();
        assertThat(gateway.fetchPublication(ACCOUNT, "media-99")).isEmpty();
        assertThat(gateway.recentMedia(ACCOUNT, 1)).hasSize(1);
    }

    @Test
    void timeoutYieldsUnknownRemoteResultWithoutSecondPublishCall() {
        InMemoryInstagramGateway gateway = new InMemoryInstagramGateway();
        gateway.timeoutOnNextPublish();
        PublishCoordinator coordinator = new PublishCoordinator(
                new ConfiguredPublishEligibility("true"), gateway);

        PublishAttempt attempt = coordinator.attempt(approvedContext(), ACCOUNT, IMAGE, "Caption");

        assertThat(attempt.status()).isEqualTo(PublicationStatus.UNKNOWN_REMOTE_RESULT);
        assertThat(attempt.mediaId()).isNull();
        assertThat(attempt.reason()).contains("Reconciliation");
        assertThat(gateway.createCalls()).isEqualTo(1);
        assertThat(gateway.publishCalls()).isEqualTo(1);
        assertThat(gateway.recentMedia(ACCOUNT, 10)).isEmpty();
    }

    @Test
    void publishedFeedPostNeverClaimsAudioSuccess() {
        InMemoryInstagramGateway gateway = new InMemoryInstagramGateway();
        PublishCoordinator coordinator = new PublishCoordinator(
                new ConfiguredPublishEligibility("true"), gateway);

        PublishAttempt attempt = coordinator.attempt(approvedContext(), ACCOUNT, IMAGE, "Caption");

        assertThat(attempt.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(attempt.audioStatus()).isEqualTo(AudioStatus.NOT_VERIFIABLE);
    }

    @Test
    void flagTrueButOpenWorkflowGateStillDenies() {
        Map<String, GateResult> gates = allGatesPassed();
        gates.put("ADDITIONAL_WORKFLOW_DEFINED",
                new GateResult("ADDITIONAL_WORKFLOW_DEFINED", false, "noch offen"));
        InMemoryInstagramGateway gateway = new InMemoryInstagramGateway();
        PublishCoordinator coordinator = new PublishCoordinator(
                new ConfiguredPublishEligibility("true"), gateway);

        PublishAttempt attempt = coordinator.attempt(
                PublishContext.approvedWith(gates), ACCOUNT, IMAGE, "Caption");

        assertThat(attempt.status()).isEqualTo(PublicationStatus.BLOCKED);
        assertThat(attempt.reason()).contains("ADDITIONAL_WORKFLOW_DEFINED");
        assertThat(gateway.createCalls()).isZero();
        assertThat(gateway.publishCalls()).isZero();
    }
}
