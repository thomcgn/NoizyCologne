package de.noizycologne.backend.instagram.gateway;

import de.noizycologne.backend.instagram.ContainerStatus;
import de.noizycologne.backend.instagram.GatewayTimeoutException;
import de.noizycologne.backend.instagram.InstagramAccount;
import de.noizycologne.backend.instagram.InstagramGateway;
import de.noizycologne.backend.instagram.MediaContainerResult;
import de.noizycologne.backend.instagram.PublishResult;
import de.noizycologne.backend.instagram.RemotePublication;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Produktiv-Adapter für die Meta-Graph-API ausschließlich über Spring
 * {@link RestClient}. Der Access-Token wird nur injiziert und als
 * {@code Authorization}-Header verwendet: er wird nie geloggt, in keine URL
 * geschrieben und nicht in DTOs oder Datenbanken gespeichert.
 *
 * <p><strong>Wichtige Grenze:</strong> Diese Klasse setzt einen bereits durch
 * das Team bereitgestellten Token voraus. Sie implementiert <em>keinen</em>
 * OAuth-Code-Flow, keine Token-Vergabe und keinen Account-Austausch und
 * behauptet keinen bestandenen Meta-PoC. Die Wahl zwischen Facebook Login und
 * Instagram Login, benötigte Berechtigungen und ein echter Foto-PoC bleiben
 * eine separate, ausdrücklich freizugebende Aufgabe (ADR 0001, Kapitel 4.2).</p>
 */
public final class GraphApiInstagramGateway implements InstagramGateway {
    private static final @NonNull ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<>() { };
    private final RestClient client;

    public GraphApiInstagramGateway(RestClient.Builder builder, String baseUrl, String accessToken,
                                    Duration connectTimeout, Duration readTimeout) {
        Objects.requireNonNull(builder, "builder");
        if (!hasText(baseUrl) || !hasText(accessToken)) {
            throw new IllegalArgumentException("Base-URL und Access-Token müssen konfiguriert sein");
        }
        String url = Objects.requireNonNull(baseUrl, "baseUrl");
        String token = Objects.requireNonNull(accessToken, "accessToken");
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Gateway-Base-URL muss http oder https verwenden");
        }
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder.requestFactory(requestFactory)
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @Override
    public MediaContainerResult createPhotoContainer(InstagramAccount account, URI publicJpegUrl, String caption) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(publicJpegUrl, "publicJpegUrl");
        Objects.requireNonNull(caption, "caption");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("image_url", publicJpegUrl.toString());
        form.add("caption", caption);
        form.add("media_type", "IMAGE");
        Map<String, Object> response = Objects.requireNonNull(guard("createPhotoContainer", false, () ->
                client.post()
                        .uri(uriBuilder -> uriBuilder.pathSegment(account.id(), "media").build())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .requiredBody(OBJECT)), "response");
        return new MediaContainerResult(string(response, "id"));
    }

    @Override
    public ContainerStatus fetchContainerStatus(InstagramAccount account, String containerId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(containerId, "containerId");
        Map<String, Object> response = Objects.requireNonNull(guard("fetchContainerStatus", false, () ->
                Objects.requireNonNull(client.get()
                        .uri(uriBuilder -> uriBuilder.pathSegment(containerId)
                                .queryParam("fields", "status").build()), "request")
                        .retrieve()
                        .requiredBody(OBJECT)), "response");
        String status = string(response, "status");
        try {
            return ContainerStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new InstagramGatewayException("Unbekannter Container-Status: " + status);
        }
    }

    @Override
    public PublishResult publishContainer(InstagramAccount account, String containerId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(containerId, "containerId");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("creation_id", containerId);
        Map<String, Object> response = Objects.requireNonNull(guard("publishContainer", false, () ->
                client.post()
                        .uri(uriBuilder -> uriBuilder.pathSegment(account.id(), "media_publish").build())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .requiredBody(OBJECT)), "response");
        return new PublishResult(string(response, "id"), string(response, "permalink"));
    }

    @Override
    public Optional<@NonNull RemotePublication> fetchPublication(InstagramAccount account, String mediaId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(mediaId, "mediaId");
        @Nullable Map<String, Object> response = guard("fetchPublication", true, () ->
                Objects.requireNonNull(client.get()
                        .uri(uriBuilder -> uriBuilder.pathSegment(mediaId)
                                .queryParam("fields", "id,permalink,caption").build()), "request")
                        .retrieve()
                        .requiredBody(OBJECT));
        if (response == null) {
            return Optional.empty();
        }
        Map<String, Object> body = Objects.requireNonNull(response, "response");
        return Optional.of(remote(body));
    }

    @Override
    public List<RemotePublication> recentMedia(InstagramAccount account, int limit) {
        Objects.requireNonNull(account, "account");
        int bounded = Math.max(1, Math.min(limit, 100));
        Map<String, Object> response = Objects.requireNonNull(guard("recentMedia", false, () ->
                Objects.requireNonNull(client.get()
                        .uri(uriBuilder -> uriBuilder.pathSegment(account.id(), "media")
                                .queryParam("fields", "id,permalink,caption")
                                .queryParam("limit", bounded).build()), "request")
                        .retrieve()
                        .requiredBody(OBJECT)), "response");
        Object data = response.get("data");
        if (!(data instanceof List<?> items)) {
            throw new InstagramGatewayException("Graph-Antwort ohne Media-Liste");
        }
        List<RemotePublication> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add(remote(cast(map)));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> cast(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static @NonNull RemotePublication remote(Map<String, Object> response) {
        @Nullable Object caption = response.get("caption");
        return new RemotePublication(string(response, "id"), string(response, "permalink"),
                caption instanceof String text ? text : null);
    }

    private static String string(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InstagramGatewayException("Graph-Antwort ohne gültiges Feld " + key);
        }
        return text;
    }

    private static @Nullable <T> T guard(String operation, boolean notFoundAsEmpty, GatewayCall<T> call) {
        try {
            return call.call();
        } catch (ResourceAccessException exception) {
            throw translate(operation, exception);
        } catch (RestClientResponseException exception) {
            if (notFoundAsEmpty && exception.getStatusCode().value() == 404) {
                return null;
            }
            throw new InstagramGatewayException(operation + ": Graph API Status "
                    + exception.getStatusCode().value());
        }
    }

    private static RuntimeException translate(String operation, ResourceAccessException exception) {
        for (@Nullable Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return new GatewayTimeoutException(operation + ": Timeout an der Meta-Grenze");
            }
        }
        return new InstagramGatewayException(operation + ": Meta-Grenze nicht erreichbar");
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface GatewayCall<T> {
        T call();
    }
}
