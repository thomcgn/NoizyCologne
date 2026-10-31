package de.noizycologne.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

@Embeddable
public class PartyDjAssignment {

    @Column(name = "actor_id", nullable = false, length = 100)
    private String actorId;

    @Column(name = "assignment", nullable = false, length = 20)
    private String assignment;

    protected PartyDjAssignment() {
    }

    public PartyDjAssignment(String actorId, String assignment) {
        this.actorId = actorId;
        this.assignment = assignment;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAssignment() {
        return assignment;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PartyDjAssignment that)) return false;
        return Objects.equals(actorId, that.actorId)
                && Objects.equals(assignment, that.assignment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actorId, assignment);
    }
}
