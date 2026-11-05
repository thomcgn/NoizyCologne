package de.noizycologne.backend.cancellation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/cancellations")
@org.springframework.context.annotation.Profile({"local","staging"})
public class CancellationController {
    private final CancellationService service;
    public CancellationController(CancellationService service) { this.service=service; }
    @GetMapping public List<Map<String,Object>> list() { return service.list(); }
    public record Confirm(@NotBlank String calendarEntryId,String changeId,@NotBlank String trigger,@NotBlank String reasonCode,String replacementCandidateId) { }
    @PostMapping public Map<String,String> confirm(@Valid @RequestBody Confirm request) {
        return Map.of("id",service.confirm(request.calendarEntryId(),request.changeId(),request.trigger(),request.reasonCode(),request.replacementCandidateId()));
    }
    @PostMapping("/{id}/graphic") public Map<String,String> prepare(@PathVariable String id) throws java.io.IOException { return Map.of("mediaAssetId",service.prepareGraphic(id)); }
    @GetMapping(value="/{id}/graphic",produces="image/png") public FileSystemResource graphic(@PathVariable String id) { return service.graphic(id); }
    public record Announcement(@NotNull @Size(max=12000) String caption,@NotBlank String status,@PositiveOrZero long revision) { }
    @PutMapping("/{id}/announcement") public void update(@PathVariable String id,@Valid @RequestBody Announcement request) { service.updateAnnouncement(id,request.caption(),request.status(),request.revision()); }
    @GetMapping("/{id}/history") public List<Map<String,Object>> history(@PathVariable String id) { return service.history(id); }
}
