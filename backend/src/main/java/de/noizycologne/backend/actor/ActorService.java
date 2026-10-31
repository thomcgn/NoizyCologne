package de.noizycologne.backend.actor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class ActorService {

    private static final Set<String> KINDS = Set.of("person", "collective");
    private static final Set<String> ROLES = Set.of("dj", "organizer");

    private final ActorRepository actorRepository;

    public ActorService(ActorRepository actorRepository) {
        this.actorRepository = actorRepository;
    }

    @Transactional(readOnly = true)
    public List<ActorResponse> getActors() {
        return actorRepository.findAll().stream()
                .map(ActorResponse::from)
                .toList();
    }

    @Transactional
    public ActorResponse createActor(ActorRequest request) {
        validateRequest(request);
        ensureNameAvailable(request.name(), null);
        validateMemberships(request.kind(), request.collectiveIds());

        Actor actor = new Actor(
                request.name().trim(),
                request.kind(),
                request.instagram(),
                request.roles(),
                request.collectiveIds()
        );
        return ActorResponse.from(actorRepository.save(actor));
    }

    @Transactional
    public ActorResponse updateActor(String id, ActorRequest request) {
        validateRequest(request);
        Actor actor = actorRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Akteur nicht gefunden"));

        ensureNameAvailable(request.name(), id);
        validateMemberships(request.kind(), request.collectiveIds());
        if ("person".equals(request.kind())
                && "collective".equals(actor.getKind())
                && actorRepository.countMembershipsAsCollective(id) > 0) {
            throw badRequest("Ein Kollektiv mit bestehenden Mitgliedern kann nicht zur Person werden");
        }

        actor.update(
                request.name().trim(),
                request.kind(),
                request.instagram(),
                request.roles(),
                request.collectiveIds()
        );
        return ActorResponse.from(actorRepository.save(actor));
    }

    private void validateRequest(ActorRequest request) {
        if (!KINDS.contains(request.kind())) {
            throw badRequest("kind muss person oder collective sein");
        }
        if (!ROLES.containsAll(request.roles())) {
            throw badRequest("roles enthält eine unbekannte Rolle");
        }
    }

    private void validateMemberships(String kind, Collection<String> collectiveIds) {
        if ("collective".equals(kind) && !collectiveIds.isEmpty()) {
            throw badRequest("Nur Personen können Mitgliedschaften in Kollektiven haben");
        }

        if (collectiveIds.isEmpty()) {
            return;
        }

        List<Actor> collectives = actorRepository.findAllById(collectiveIds);
        if (collectives.size() != collectiveIds.size()
                || collectives.stream().anyMatch(actor -> !"collective".equals(actor.getKind()))) {
            throw badRequest("Jede collectiveId muss auf ein bestehendes Kollektiv verweisen");
        }
    }

    private void ensureNameAvailable(String name, String currentId) {
        actorRepository.findByNameIgnoreCase(name.trim())
                .filter(actor -> !actor.getId().equals(currentId))
                .ifPresent(actor -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Akteurname existiert bereits");
                });
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
