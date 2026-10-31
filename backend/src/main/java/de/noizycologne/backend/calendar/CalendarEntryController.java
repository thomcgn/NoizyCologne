package de.noizycologne.backend.calendar;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/calendar-entries")
public class CalendarEntryController {

    private final CalendarEntryService calendarEntryService;

    public CalendarEntryController(CalendarEntryService calendarEntryService) {
        this.calendarEntryService = calendarEntryService;
    }

    @GetMapping
    public List<CalendarEntryResponse> getEntries() {
        return calendarEntryService.getEntries();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarEntryResponse createEntry(@Valid @RequestBody CalendarEntryRequest request) {
        return calendarEntryService.createEntry(request);
    }

    @PutMapping("/{id}")
    public CalendarEntryResponse updateEntry(
            @PathVariable String id,
            @Valid @RequestBody CalendarEntryRequest request
    ) {
        return calendarEntryService.updateEntry(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(@PathVariable String id) {
        calendarEntryService.deleteEntry(id);
    }
}
