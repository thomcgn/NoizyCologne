package de.noizycologne.backend.party;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartySeriesRepository extends JpaRepository<PartySeries, String> {

    Optional<PartySeries> findByNameIgnoreCase(String name);
}
