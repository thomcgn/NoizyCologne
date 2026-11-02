package de.noizycologne.backend.party;

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

import java.util.List;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/parties")
public class PartySeriesController {

    private final PartySeriesService partySeriesService;

    public PartySeriesController(PartySeriesService partySeriesService) {
        this.partySeriesService = partySeriesService;
    }

    @GetMapping
    public List<PartySeriesResponse> getParties() {
        return partySeriesService.getParties();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PartySeriesResponse createParty(@Valid @RequestBody PartySeriesRequest request) {
        return partySeriesService.createParty(request);
    }

    @PutMapping("/{id}")
    public PartySeriesResponse updateParty(
            @PathVariable String id,
            @Valid @RequestBody PartySeriesRequest request
    ) {
        return partySeriesService.updateParty(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteParty(@PathVariable String id) {
        partySeriesService.deleteParty(id);
    }
}
