package de.noizycologne.backend.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Internal only: Caddy does not route /internal to the API. Uses its own server-to-server key. */
@RestController
@RequestMapping("/internal/auth")
public final class AuthStoreController {
    private final AuthSessionStore store;
    private final byte[] secret;

    public AuthStoreController(AuthSessionStore store, @Value("${noizy.auth.service-secret:}") String secret) {
        this.store = store;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record Session(@Pattern(regexp="[0-9a-f]{64}") String nonceHash,
                          @Pattern(regexp="[0-9a-f]{64}") String generation, long expiresAt) { }
    public record Keys(@Pattern(regexp="[0-9a-f]{64}") String accountHash,
                       @Pattern(regexp="[0-9a-f]{64}") String addressHash) { }
    public record Validity(boolean valid) { }

    @PostMapping("/register")
    public ResponseEntity<@NonNull Validity> register(@RequestHeader(name="X-Noizy-Auth-Key", required=false) @Nullable String key,
                                             @Valid @RequestBody Session session) {
        authenticate(key);
        store.register(session.nonceHash(), session.generation(), session.expiresAt());
        return result(new Validity(true));
    }

    @PostMapping("/validate")
    public ResponseEntity<@NonNull Validity> validate(@RequestHeader(name="X-Noizy-Auth-Key", required=false) @Nullable String key,
                                             @Valid @RequestBody Session session) {
        authenticate(key);
        return result(new Validity(store.validate(session.nonceHash(), session.generation())));
    }

    @PostMapping("/revoke")
    public ResponseEntity<@NonNull Validity> revoke(@RequestHeader(name="X-Noizy-Auth-Key", required=false) @Nullable String key,
                                           @Valid @RequestBody Session session) {
        authenticate(key);
        store.revoke(session.nonceHash());
        return result(new Validity(false));
    }

    @PostMapping("/attempt")
    public ResponseEntity<AuthSessionStore.@NonNull Attempt> attempt(@RequestHeader(name="X-Noizy-Auth-Key", required=false) @Nullable String key,
                                                            @Valid @RequestBody Keys keys) {
        authenticate(key);
        return result(store.attempt(keys.accountHash(), keys.addressHash()));
    }

    @PostMapping("/clear")
    public ResponseEntity<@NonNull Validity> clear(@RequestHeader(name="X-Noizy-Auth-Key", required=false) @Nullable String key,
                                          @Valid @RequestBody Keys keys) {
        authenticate(key);
        store.clear(keys.accountHash(), keys.addressHash());
        return result(new Validity(true));
    }

    private void authenticate(@Nullable String provided) {
        if (secret.length < 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Anmeldespeicher nicht eingerichtet.");
        if (provided == null || !MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nicht autorisiert.");
    }

    private static <T> ResponseEntity<T> result(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
