package de.noizycologne.backend.genre;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GenreAliasRepository extends JpaRepository<GenreAlias, String> {
    List<@NonNull GenreAlias> findAllByGenreIdIn(Collection<String> genreIds);
    List<@NonNull GenreAlias> findAllByOrderByValueAsc();
}
