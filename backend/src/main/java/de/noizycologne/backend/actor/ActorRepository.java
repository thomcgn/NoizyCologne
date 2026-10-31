package de.noizycologne.backend.actor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ActorRepository extends JpaRepository<Actor, String> {

    Optional<Actor> findByNameIgnoreCase(String name);

    @Query(value = "select count(*) from actor_collective_memberships where collective_id = :actorId", nativeQuery = true)
    long countMembershipsAsCollective(String actorId);
}
