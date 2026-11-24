package de.noizycologne.backend.instagram;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministisches In-Memory-Gateway – ausschließlich unter {@code src/test/java}.
 * Kein Netzwerkzugriff, kein OAuth, keine Tokens, kein Produktiv-Bean.
 */
final class InMemoryInstagramGateway implements InstagramGateway {
    private final Map<String, ContainerStatus> containers = new LinkedHashMap<>();
    private final Map<String, PublishResult> publications = new LinkedHashMap<>();
    private int createCalls;
    private int statusCalls;
    private int publishCalls;
    private boolean timeoutOnPublish;

    @Override
    public MediaContainerResult createPhotoContainer(InstagramAccount account, URI publicJpegUrl, String caption) {
        createCalls++;
        String containerId = "container-" + createCalls;
        containers.put(containerId, ContainerStatus.FINISHED);
        return new MediaContainerResult(containerId);
    }

    @Override
    public ContainerStatus fetchContainerStatus(InstagramAccount account, String containerId) {
        statusCalls++;
        @Nullable ContainerStatus status = containers.get(containerId);
        if (status == null) {
            throw new IllegalArgumentException("Unbekannter Container: " + containerId);
        }
        return status;
    }

    @Override
    public PublishResult publishContainer(InstagramAccount account, String containerId) {
        publishCalls++;
        if (timeoutOnPublish) {
            throw new GatewayTimeoutException("Simulierter Timeout beim media_publish");
        }
        String mediaId = "media-" + publishCalls;
        PublishResult result = new PublishResult(mediaId, "https://www.instagram.com/p/" + mediaId + "/");
        publications.put(mediaId, result);
        return result;
    }

    @Override
    public Optional<@NonNull RemotePublication> fetchPublication(InstagramAccount account, String mediaId) {
        @Nullable PublishResult result = publications.get(mediaId);
        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(new RemotePublication(result.mediaId(), result.permalink(), null));
    }

    @Override
    public List<RemotePublication> recentMedia(InstagramAccount account, int limit) {
        List<RemotePublication> result = new ArrayList<>();
        for (PublishResult publication : publications.values()) {
            if (result.size() >= limit) {
                break;
            }
            result.add(new RemotePublication(publication.mediaId(), publication.permalink(), null));
        }
        return result;
    }

    void timeoutOnNextPublish() {
        this.timeoutOnPublish = true;
    }

    int createCalls() {
        return createCalls;
    }

    int statusCalls() {
        return statusCalls;
    }

    int publishCalls() {
        return publishCalls;
    }
}
