package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.store.PublishJobStore;
import de.noizycologne.backend.instagram.worker.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTests {
    @Test
    void reconciliationUsesOnlyReadOnlyGatewayMethods() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        PublishJobStore store = mock(PublishJobStore.class);
        var publication = new RemotePublication("media-1", "https://instagram.example/p/media-1",
                "Freigegebene Caption");
        when(gateway.fetchPublication(any(), eq("media-1"))).thenReturn(Optional.of(publication));

        var outcome = new ReconciliationService(store, gateway, 25)
                .reconcile(PublishTestFixtures.unknownJobWithMedia());

        assertThat(outcome).isEqualTo(ReconciliationService.Outcome.CONFIRMED);
        verify(gateway).fetchPublication(any(), eq("media-1"));
        verify(store).confirmReconciled(any(), eq(publication), any());
        verify(gateway, never()).publishContainer(any(), any());
        verify(gateway, never()).createPhotoContainer(any(), any(), any());
        verify(gateway, never()).fetchContainerStatus(any(), any());
    }

    @Test
    void missingRemotePublicationKeepsJobUnknownWithoutRepublishing() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        PublishJobStore store = mock(PublishJobStore.class);
        when(gateway.fetchPublication(any(), any())).thenReturn(Optional.empty());
        doReturn(List.of(new RemotePublication("other", "https://instagram.example/p/other",
                "Andere Caption"))).when(gateway).recentMedia(any(), eq(25));

        var outcome = new ReconciliationService(store, gateway, 25)
                .reconcile(PublishTestFixtures.claimedJob());

        assertThat(outcome).isEqualTo(ReconciliationService.Outcome.UNRESOLVED);
        verify(store).keepUnknown(eq("job-1"), any());
        verify(gateway, never()).publishContainer(any(), any());
        verify(store, never()).confirmReconciled(any(), any(), any());
    }

    @Test
    void recentMediaCanConfirmAnUnknownJobWithoutRepublishing() {
        InstagramGateway gateway = mock(InstagramGateway.class);
        PublishJobStore store = mock(PublishJobStore.class);
        var publication = new RemotePublication("media-2", "https://instagram.example/p/media-2",
                "Freigegebene Caption");
        when(gateway.fetchPublication(any(), any())).thenReturn(Optional.empty());
        doReturn(List.of(publication)).when(gateway).recentMedia(any(), eq(25));

        var outcome = new ReconciliationService(store, gateway, 25)
                .reconcile(PublishTestFixtures.claimedJob());

        assertThat(outcome).isEqualTo(ReconciliationService.Outcome.CONFIRMED);
        verify(store).confirmReconciled(any(), eq(publication), any());
        verify(gateway, never()).publishContainer(any(), any());
    }
}
