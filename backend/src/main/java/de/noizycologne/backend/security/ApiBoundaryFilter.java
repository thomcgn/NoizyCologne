package de.noizycologne.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/** Internal proxy authentication supplements Caddy session authentication; never trusts a client token. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ApiBoundaryFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Noizy-Api-Key";
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final byte[] active;
    private final byte[] previous;
    private final String origin;
    private final String authority;

    public ApiBoundaryFilter(@Value("${noizy.api.trust.secret:}") @NonNull String secret,
            @Value("${noizy.api.trust.previous-secret:}") @NonNull String previousSecret,
            @Value("${noizy.api.public-origin:https://tsa.noizycologne.de}") @NonNull String publicOrigin) {
        active = secret.getBytes(StandardCharsets.UTF_8);
        previous = previousSecret.getBytes(StandardCharsets.UTF_8);
        var target = URI.create(publicOrigin);
        if (!"https".equals(target.getScheme()) || target.getHost() == null || target.getRawUserInfo() != null
                || target.getRawQuery() != null || target.getRawFragment() != null || !target.getPath().isEmpty())
            throw new IllegalArgumentException("API public origin must be one explicit HTTPS origin without a path");
        origin = publicOrigin;
        authority = java.util.Objects.requireNonNull(target.getRawAuthority());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // Caddy never exposes these endpoints. Factory reset keeps its separate key and reauthentication.
        boolean health = "GET".equals(request.getMethod()) && Set.of("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness").contains(path);
        if (health || path.equals("/internal/maintenance/factory-reset")) {
            chain.doFilter(request, response); return;
        }
        @Nullable String provided = request.getHeader(HEADER);
        byte[] value = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        boolean valid = (active.length >= 32 && MessageDigest.isEqual(active, value))
                | (previous.length >= 32 && MessageDigest.isEqual(previous, value));
        if (!valid) { deny(response, 403); return; }
        // Values are fixed by Caddy and compared to configuration, not used to infer a trusted origin.
        if (!authority.equals(request.getHeader("X-Noizy-Proxy-Host")) || !"https".equals(request.getHeader("X-Noizy-Proxy-Proto"))) {
            deny(response, 403); return;
        }
        if (MUTATIONS.contains(request.getMethod())) {
            @Nullable String site = request.getHeader("Sec-Fetch-Site");
            if (!origin.equals(request.getHeader("Origin")) || site != null && !site.equals("same-origin")) {
                deny(response, 403); return;
            }
            @Nullable String type = request.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?|multipart/form-data(?:\\s*;.*)?")) {
                deny(response, 415); return;
            }
            if (type == null && request.getContentLengthLong() > 0) { deny(response, 415); return; }
        }
        chain.doFilter(request, response);
    }

    private static void deny(@NonNull HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Anfrage nicht autorisiert.\"}");
    }
}
