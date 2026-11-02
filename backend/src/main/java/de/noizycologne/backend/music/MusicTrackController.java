package de.noizycologne.backend.music;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/music/tracks")
public class MusicTrackController {

    private final MusicTrackService service;

    public MusicTrackController(MusicTrackService service) {
        this.service = service;
    }

    @GetMapping
    public List<MusicTrackResponse> getTracks(@RequestParam(required = false) String calendarEntryId) {
        return service.getSuggestions(calendarEntryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MusicTrackResponse create(@Valid @RequestBody MusicTrackRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public MusicTrackResponse update(@PathVariable String id, @Valid @RequestBody MusicTrackRequest request) {
        return service.update(id, request);
    }
}
