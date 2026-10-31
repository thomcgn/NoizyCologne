package de.noizycologne.backend.venue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, String> {

    boolean existsByNameIgnoreCase(String name);
}
