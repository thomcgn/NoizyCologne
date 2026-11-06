package de.noizycologne.backend.genre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface GenreRepository extends JpaRepository<Genre, String> {
    List<@org.jspecify.annotations.NonNull Genre> findAllByActiveTrueOrderByCanonicalNameAsc();
    List<@org.jspecify.annotations.NonNull Genre> findAllByOrderByCanonicalNameAsc();
    List<@org.jspecify.annotations.NonNull Genre> findAllByDisplayNameInAndActiveTrue(Collection<String> displayNames);
}
