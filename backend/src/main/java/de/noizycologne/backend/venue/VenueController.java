package de.noizycologne.backend.venue;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/venues")
public class VenueController {

    private final VenueRepository venueRepository;
    private final VenueService venueService;

    public VenueController(VenueRepository venueRepository, VenueService venueService) {
        this.venueRepository = venueRepository;
        this.venueService = venueService;
    }

    @GetMapping
    public List<VenueResponse> getVenues() {
        return venueRepository.findAll().stream()
                .map(venue -> VenueResponse.from(venue))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse createVenue(@Valid @RequestBody VenueRequest request) {
        Venue venue = new Venue(request.name().trim(), request.instagram());
        return VenueResponse.from(venueRepository.save(venue));
    }

    @PutMapping("/{id}")
    public VenueResponse updateVenue(@PathVariable String id, @Valid @RequestBody VenueRequest request) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue nicht gefunden"));
        venue.update(request.name().trim(), request.instagram());
        return VenueResponse.from(venueRepository.save(venue));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVenue(@PathVariable String id) {
        venueService.deleteVenue(id);
    }
}
