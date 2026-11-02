package de.noizycologne.backend.actor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ActorRepository extends JpaRepository<Actor, String> {

    Optional<Actor> findByNameIgnoreCase(String name);

    @Query(value = "select count(*) from actor_collective_memberships where collective_id = :actorId", nativeQuery = true)
    long countMembershipsAsCollective(String actorId);

    @Query(value = "select count(*) from party_djs where actor_id = :actorId", nativeQuery = true)
    long countPartyDjReferences(String actorId);

    @Query(value = "select count(*) from party_organizers where actor_id = :actorId", nativeQuery = true)
    long countPartyOrganizerReferences(String actorId);

    @Query(value = "select count(*) from calendar_entry_mentions where actor_id = :actorId", nativeQuery = true)
    long countCalendarMentionReferences(String actorId);

    @Query(value = "select count(*) from editorial_post_draft_mentions where actor_id = :actorId", nativeQuery = true)
    long countEditorialMentionReferences(String actorId);

    @Query(value = "select count(*) from actor_collective_memberships where person_id = :actorId", nativeQuery = true)
    long countMembershipsAsPerson(String actorId);
}
