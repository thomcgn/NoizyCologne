package de.noizycologne.backend.instagram.gateway;

import de.noizycologne.backend.instagram.InstagramGateway;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Das Produktiv-Gateway entsteht nur bei explizitem Opt-in <em>und</em> vorhandenem
 * Token. Ohne diese Voraussetzungen bleibt eine fail-closed Bean aktiv, die jede
 * Operation verweigert. Das Publishing-Flag allein aktiviert keinen HTTP-Aufruf.
 */
@Configuration
public class InstagramGatewayConfiguration {
    @Bean
    @ConditionalOnProperty(name = "noizy.instagram.gateway.enabled", havingValue = "true")
    InstagramGateway graphApiInstagramGateway(
            RestClient.Builder builder,
            @Value("${noizy.instagram.gateway.base-url:https://graph.facebook.com/v21.0}") String baseUrl,
            @Value("${noizy.instagram.access-token:${INSTAGRAM_ACCESS_TOKEN:}}") @Nullable String accessToken,
            @Value("${noizy.instagram.gateway.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${noizy.instagram.gateway.read-timeout-ms:10000}") long readTimeoutMs) {
        if (accessToken == null || accessToken.isBlank()) {
            return new FailClosedInstagramGateway("kein Access-Token konfiguriert");
        }
        return new GraphApiInstagramGateway(builder, baseUrl, accessToken,
                Duration.ofMillis(Math.max(1, connectTimeoutMs)),
                Duration.ofMillis(Math.max(1, readTimeoutMs)));
    }

    @Bean
    @ConditionalOnMissingBean(InstagramGateway.class)
    InstagramGateway failClosedInstagramGateway() {
        return new FailClosedInstagramGateway("gateway.enabled=false");
    }
}
