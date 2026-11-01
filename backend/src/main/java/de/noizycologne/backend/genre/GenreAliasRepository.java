package de.noizycologne.backend.genre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GenreAliasRepository extends JpaRepository<GenreAlias, String> {
    List<GenreAlias> findAllByGenreIdIn(Collection<String> genreIds);
    List<GenreAlias> findAllByOrderByValueAsc();
}
