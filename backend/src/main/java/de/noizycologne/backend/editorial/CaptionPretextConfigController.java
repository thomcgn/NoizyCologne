package de.noizycologne.backend.editorial;

import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Geschützt durch den bestehenden ApiBoundaryFilter (API-Key, Origin und Proxy-Header). */
@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/editorial-config")
public class CaptionPretextConfigController {

    private final CaptionPretextConfigService service;

    public CaptionPretextConfigController(CaptionPretextConfigService service) {
        this.service = service;
    }

    @GetMapping("/caption-pretext")
    public ResponseEntity<@NonNull CaptionPretextResponse> get() {
        return noStore(service.get());
    }

    @PutMapping("/caption-pretext")
    public ResponseEntity<@NonNull CaptionPretextResponse> update(@Valid @RequestBody CaptionPretextRequest request) {
        return noStore(service.update(request));
    }

    private static ResponseEntity<@NonNull CaptionPretextResponse> noStore(CaptionPretextResponse value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
