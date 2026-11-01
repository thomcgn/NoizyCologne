package de.noizycologne.backend.editorial;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1")
public class EditorialPostDraftController {

    private final EditorialPostDraftService service;

    public EditorialPostDraftController(EditorialPostDraftService service) {
        this.service = service;
    }

    @GetMapping("/editorial-drafts")
    public List<EditorialPostDraftResponse> getDrafts() {
        return service.getDrafts();
    }

    @PostMapping("/calendar-entries/{calendarEntryId}/editorial-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public EditorialPostDraftResponse create(
            @PathVariable String calendarEntryId,
            @Valid @RequestBody EditorialPostDraftRequest request) {
        return service.create(calendarEntryId, request);
    }

    @PutMapping("/editorial-drafts/{id}")
    public EditorialPostDraftResponse update(
            @PathVariable String id,
            @Valid @RequestBody EditorialPostDraftRequest request) {
        return service.update(id, request);
    }
}
