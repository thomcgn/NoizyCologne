package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.gates.PublishFactReader;
import de.noizycologne.backend.instagram.gates.ServerSideGateEvaluator;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import de.noizycologne.backend.instagram.worker.PublishJobProcessor;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishJobProcessorTests {
    private static final InstagramAccount ACCOUNT = new InstagramAccount("account-1");

    @Test
    void publishTimeoutYieldsUnknownRemoteResultWithoutSecondPublishCall() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        when(gateway.createPhotoContainer(any(), any(), any()))
                .thenReturn(new MediaContainerResult("container-1"));
        when(gateway.fetchContainerStatus(any(), eq("container-1"))).thenReturn(ContainerStatus.FINISHED);
        when(gateway.publishContainer(any(), eq("container-1")))
                .thenThrow(new GatewayTimeoutException("simulierter Timeout"));
        PublishJobStore store = mock(PublishJobStore.class);
        PublishFactReader reader = mock(PublishFactReader.class);
        when(reader.load(any())).thenReturn(PublishTestFixtures.approvedFacts());
        var processor = new PublishJobProcessor(store, reader, new ServerSideGateEvaluator(),
                new ConfiguredPublishEligibility("true"), gateway,
                "https://media.example/flyers", 3, 0);

        PublishAttempt attempt = processor.process(PublishTestFixtures.claimedJob());

        assertThat(attempt.status()).isEqualTo(PublicationStatus.UNKNOWN_REMOTE_RESULT);
        assertThat(attempt.mediaId()).isNull();
        assertThat(attempt.reason()).contains("Reconciliation");
        assertThat(attempt.audioStatus()).isEqualTo(AudioStatus.NOT_VERIFIABLE);
        verify(gateway, times(1)).publishContainer(any(), eq("container-1"));
        verify(store, times(1)).completeUnknown(any(), any());
        verify(store, never()).completePublished(any(), any());
    }

    @Test
    void deniedEligibilityNeverCallsTheGateway() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        PublishJobStore store = mock(PublishJobStore.class);
        PublishFactReader reader = mock(PublishFactReader.class);
        when(reader.load(any())).thenReturn(PublishTestFixtures.approvedFacts());
        var processor = new PublishJobProcessor(store, reader, new ServerSideGateEvaluator(),
                new ConfiguredPublishEligibility("false"), gateway,
                "https://media.example/flyers", 3, 0);

        PublishAttempt attempt = processor.process(PublishTestFixtures.claimedJob());

        assertThat(attempt.status()).isEqualTo(PublicationStatus.BLOCKED);
        verify(gateway, never()).createPhotoContainer(any(), any(), any());
        verify(gateway, never()).publishContainer(any(), any());
        verify(store).transition(any(), eq(PublicationStatus.CLAIMED),
                eq(PublicationStatus.BLOCKED), any(), eq(true));
    }

    @Test
    void successfulPublishPersistsOutcomeExactlyOnce() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        when(gateway.createPhotoContainer(any(), any(), any()))
                .thenReturn(new MediaContainerResult("container-1"));
        when(gateway.fetchContainerStatus(any(), eq("container-1"))).thenReturn(ContainerStatus.FINISHED);
        when(gateway.publishContainer(any(), eq("container-1")))
                .thenReturn(new PublishResult("media-1", "https://instagram.example/p/media-1"));
        PublishJobStore store = mock(PublishJobStore.class);
        PublishFactReader reader = mock(PublishFactReader.class);
        when(reader.load(any())).thenReturn(PublishTestFixtures.approvedFacts());
        var processor = new PublishJobProcessor(store, reader, new ServerSideGateEvaluator(),
                new ConfiguredPublishEligibility("true"), gateway,
                "https://media.example/flyers", 3, 0);

        PublishAttempt attempt = processor.process(PublishTestFixtures.claimedJob());

        assertThat(attempt.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(attempt.mediaId()).isEqualTo("media-1");
        assertThat(attempt.audioStatus()).isEqualTo(AudioStatus.NOT_VERIFIABLE);
        verify(gateway, times(1)).publishContainer(eq(ACCOUNT), eq("container-1"));
        verify(store).completePublished(any(), any());
        verify(gateway).createPhotoContainer(eq(ACCOUNT),
                eq(URI.create("https://media.example/flyers/media-1.jpg")),
                eq("Freigegebene Caption\n\n#noizycologne"));
    }
}
