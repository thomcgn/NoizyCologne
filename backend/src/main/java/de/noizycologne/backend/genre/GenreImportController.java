package de.noizycologne.backend.genre;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@Profile({"local", "staging"})
@RestController
public class GenreImportController {
    private final GenreImportService service;
    public GenreImportController(GenreImportService service) { this.service = service; }

    @PostMapping(value = "/api/v1/genres/import/preview", consumes = "application/json")
    public ResponseEntity<GenreImportService.Report> preview(HttpServletRequest request) throws IOException {
        return run(request, false);
    }
    @PostMapping(value = "/api/v1/genres/import", consumes = "application/json")
    public ResponseEntity<GenreImportService.Report> apply(HttpServletRequest request) throws IOException {
        return run(request, true);
    }
    private ResponseEntity<GenreImportService.Report> run(HttpServletRequest request, boolean apply) throws IOException {
        if (request.getContentLengthLong() > GenreImportCatalog.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Import höchstens 1 MiB");
        byte[] body = request.getInputStream().readNBytes(GenreImportCatalog.MAX_BYTES + 1);
        var report = service.evaluate(GenreImportCatalog.parse(body), apply);
        return ResponseEntity.status(apply && !report.valid() ? HttpStatus.CONFLICT : HttpStatus.OK)
                .header("Cache-Control", "no-store").body(report);
    }
}
