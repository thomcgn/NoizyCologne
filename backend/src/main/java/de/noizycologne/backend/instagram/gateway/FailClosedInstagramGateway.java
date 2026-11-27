package de.noizycologne.backend.instagram.gateway;

import de.noizycologne.backend.instagram.ContainerStatus;
import de.noizycologne.backend.instagram.InstagramAccount;
import de.noizycologne.backend.instagram.InstagramGateway;
import de.noizycologne.backend.instagram.MediaContainerResult;
import de.noizycologne.backend.instagram.PublishResult;
import de.noizycologne.backend.instagram.RemotePublication;
import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Default-Implementierung: verweigert jede Operation ohne Netzwerkzugriff.
 * Ein Produktiv-Gateway entsteht nur bei ausdrücklich gesetztem Flag
 * {@code noizy.instagram.gateway.enabled=true} und vorhandenem Token.
 */
public final class FailClosedInstagramGateway implements InstagramGateway {
    private final String reason;

    public FailClosedInstagramGateway(String reason) {
        this.reason = reason;
    }

    @Override
    public MediaContainerResult createPhotoContainer(InstagramAccount account, URI publicJpegUrl, String caption) {
        throw denied();
    }

    @Override
    public ContainerStatus fetchContainerStatus(InstagramAccount account, String containerId) {
        throw denied();
    }

    @Override
    public PublishResult publishContainer(InstagramAccount account, String containerId) {
        throw denied();
    }

    @Override
    public Optional<@NonNull RemotePublication> fetchPublication(InstagramAccount account, String mediaId) {
        throw denied();
    }

    @Override
    public List<RemotePublication> recentMedia(InstagramAccount account, int limit) {
        throw denied();
    }

    private IllegalStateException denied() {
        return new IllegalStateException("Instagram-Gateway nicht freigegeben: " + reason
                + ". Keine HTTP-Aufrufe, kein OAuth und kein Live-Posting.");
    }
}
