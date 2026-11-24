package de.noizycologne.backend.party;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartySeriesRepository extends JpaRepository<@NonNull PartySeries, String> {

    Optional<PartySeries> findByNameIgnoreCase(String name);

    @Override
    @EntityGraph(attributePaths = {"standardGenres", "djAssignments", "organizerIds"})
    @NonNull List<@NonNull PartySeries> findAll();

    @Override
    @EntityGraph(attributePaths = {"standardGenres", "djAssignments", "organizerIds"})
    @NonNull Optional<@NonNull PartySeries> findById(String id);
}
