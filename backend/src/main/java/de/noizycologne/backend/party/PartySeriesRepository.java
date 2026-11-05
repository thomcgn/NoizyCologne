package de.noizycologne.backend.party;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

public interface PartySeriesRepository extends JpaRepository<@NonNull PartySeries, String> {

    Optional<PartySeries> findByNameIgnoreCase(String name);
}
