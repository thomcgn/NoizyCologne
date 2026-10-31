package de.noizycologne.backend.actor;

import java.util.List;

public record ActorResponse(
        String id,
        String name,
        String kind,
        List<String> roles,
        String instagram,
        List<String> collectiveIds
) {

    public static ActorResponse from(Actor actor) {
        return new ActorResponse(
                actor.getId(),
                actor.getName(),
                actor.getKind(),
                actor.getRoles().stream().sorted().toList(),
                actor.getInstagram(),
                actor.getCollectiveIds().stream().sorted().toList()
        );
    }
}
