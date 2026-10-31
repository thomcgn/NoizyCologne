package de.noizycologne.backend.actor;

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
@RequestMapping("/api/v1/actors")
public class ActorController {

    private final ActorService actorService;

    public ActorController(ActorService actorService) {
        this.actorService = actorService;
    }

    @GetMapping
    public List<ActorResponse> getActors() {
        return actorService.getActors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActorResponse createActor(@Valid @RequestBody ActorRequest request) {
        return actorService.createActor(request);
    }

    @PutMapping("/{id}")
    public ActorResponse updateActor(
            @PathVariable String id,
            @Valid @RequestBody ActorRequest request
    ) {
        return actorService.updateActor(id, request);
    }
}
