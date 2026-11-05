package de.noizycologne.backend.genre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface GenreRepository extends JpaRepository<Genre, String> {
    List<Genre> findAllByActiveTrueOrderByCanonicalNameAsc();
    List<Genre> findAllByOrderByCanonicalNameAsc();
    List<Genre> findAllByDisplayNameInAndActiveTrue(Collection<String> displayNames);
}
