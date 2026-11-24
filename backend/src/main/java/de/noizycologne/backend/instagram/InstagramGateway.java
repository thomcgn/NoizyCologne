package de.noizycologne.backend.instagram;

import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Grenze zu Meta/Instagram (Verträge nach docs/PROJECT.md, Kapitel 8.2).
 *
 * <p>Diese Skizze deklariert ausschließlich die Schnittstelle. Eine
 * Produktivimplementierung mit echten HTTP-Aufrufen, OAuth oder Tokens ist
 * <em>nicht</em> Bestandteil dieses Pakets und benötigt eine separate,
 * ausdrückliche fachliche Freigabe sowie einen bestandenen Meta-PoC.
 * Siehe docs/adr/0001-instagram-publishing-boundary.md.</p>
 */
public interface InstagramGateway {
    MediaContainerResult createPhotoContainer(InstagramAccount account, URI publicJpegUrl, String caption);

    ContainerStatus fetchContainerStatus(InstagramAccount account, String containerId);

    PublishResult publishContainer(InstagramAccount account, String containerId);

    Optional<@NonNull RemotePublication> fetchPublication(InstagramAccount account, String mediaId);

    List<RemotePublication> recentMedia(InstagramAccount account, int limit);
}
