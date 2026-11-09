package de.noizycologne.backend.maintenance;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * NEVER exposed by Caddy: the public reverse proxy only routes /api/v1/*
 * to the backend. Both requests also require a dedicated server-to-server key.
 */
@RestController
@RequestMapping("/internal/maintenance/factory-reset")
public class FactoryResetController {
    private static final String CONFIRMATION = "Kompletter Reset";
    private final FactoryResetService service;
    private final String configuredSecret;

    public FactoryResetController(FactoryResetService service,
                                  @Value("${noizy.factory-reset.secret:}") String configuredSecret) {
        this.service = service;
        this.configuredSecret = configuredSecret;
    }

    public record Confirmation(String phrase, @Nullable String revision) { }

    @GetMapping
    public FactoryResetService.Preview preview(
            @RequestHeader(name="X-Noizy-Reset-Key", required=false) @Nullable String provided) {
        authenticate(provided);
        return service.preview();
    }

    @PostMapping
    public FactoryResetService.Result reset(
            @RequestHeader(name="X-Noizy-Reset-Key", required=false) @Nullable String provided,
            @RequestBody Confirmation request) {
        authenticate(provided);
        if (request == null || !CONFIRMATION.equals(request.phrase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Zur Bestätigung exakt „Kompletter Reset“ eingeben.");
        String revision = request.revision();
        if (revision == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Die geprüfte Vorschau fehlt. Bitte neu öffnen.");
        return service.execute(revision);
    }

    private void authenticate(@Nullable String provided) {
        if (configuredSecret.length() < 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Werkseinstellungen sind serverseitig nicht eingerichtet.");
        if (provided == null || !MessageDigest.isEqual(
                configuredSecret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nicht autorisiert.");
    }
}
