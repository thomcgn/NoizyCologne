package de.noizycologne.backend.maintenance;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

/** Includes GETs because calendar reads can extend recurrence plans and media streams use files. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class MutationGuardFilter extends OncePerRequestFilter {
    private final MaintenanceGate gate;
    private final JsonMapper json = JsonMapper.builder().build();
    public MutationGuardFilter(MaintenanceGate gate) { this.gate = gate; }

    @Override protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain) throws ServletException, IOException {
        MaintenanceGate.Lease lease;
        try { lease = gate.enter(); }
        catch (ResponseStatusException exception) {
            response.setStatus(exception.getStatusCode().value());
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.writeValueAsString(Map.of("message",
                java.util.Objects.requireNonNullElse(exception.getReason(), "Wartungszugriff nicht verfügbar."))));
            return;
        }
        try { chain.doFilter(request, response); }
        finally {
            if (request.isAsyncStarted()) {
                AsyncListener completion = new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { lease.close(); }
                    @Override public void onTimeout(AsyncEvent event) { /* Work can still be running. */ }
                    @Override public void onError(AsyncEvent event) { /* Wait for completion, never unlock early. */ }
                    @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(completion); }
                catch (IllegalStateException exception) {
                    if (!request.isAsyncStarted()) lease.close();
                    else throw exception; // Keep reset blocked if completion is indeterminate.
                }
            } else lease.close();
        }
    }
}
