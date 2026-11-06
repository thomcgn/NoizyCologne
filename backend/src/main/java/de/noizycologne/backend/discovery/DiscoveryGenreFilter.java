package de.noizycologne.backend.discovery;

import de.noizycologne.backend.genre.*;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Exact, word-boundary matches against active catalogue entries and their explicit aliases. */
@Component
public class DiscoveryGenreFilter {
    private final GenreRepository genres;
    private final GenreAliasRepository aliases;
    public DiscoveryGenreFilter(GenreRepository genres, GenreAliasRepository aliases) {
        this.genres = genres; this.aliases = aliases;
    }
    @Transactional(readOnly = true)
    public Set<String> terms() {
        var active = genres.findAllByActiveTrueOrderByCanonicalNameAsc();
        Set<String> terms = new LinkedHashSet<>();
        active.forEach(g -> { terms.add(g.getCanonicalName()); terms.add(g.getDisplayName()); });
        if (!active.isEmpty()) aliases.findAllByGenreIdIn(active.stream().map(Genre::getId).toList())
                .forEach(a -> terms.add(a.getValue()));
        return Set.copyOf(terms);
    }
    @Transactional(readOnly = true)
    public List<GenreCoverage.@NonNull Genre> coverageGenres() {
        var active=genres.findAllByActiveTrueOrderByCanonicalNameAsc();
        Map<String,Set<@NonNull String>> names=new HashMap<>();
        for (var genre:active) names.put(genre.getId(),new LinkedHashSet<>(List.of(Objects.requireNonNull(genre.getCanonicalName()),Objects.requireNonNull(genre.getDisplayName()))));
        if (!active.isEmpty()) for (var alias:aliases.findAllByGenreIdIn(active.stream().map(Genre::getId).toList()))
            Objects.requireNonNull(names.get(alias.getGenreId())).add(Objects.requireNonNull(alias.getValue()));
        return active.stream().map(genre -> new GenreCoverage.Genre(genre.getId(),genre.getDisplayName(),Set.copyOf(Objects.requireNonNull(names.get(genre.getId()))))).toList();
    }
    static boolean matches(EventExtractor.Event event, Collection<String> terms) {
        return !matchingTerms(event,terms).isEmpty();
    }
    static List<String> matchingTerms(EventExtractor.Event event, Collection<String> terms) {
        if (PartyOnlyPolicy.concert(event)) return List.of();
        String text = " " + EventNormalizer.text(event.title() + " " + event.evidence()) + " ";
        Map<String,String> normalized=new TreeMap<>();
        terms.stream().sorted().filter(t -> !EventNormalizer.text(t).isBlank())
            .forEach(t -> normalized.putIfAbsent(EventNormalizer.text(t),t));
        return normalized.values().stream().filter(term -> {
            String t=EventNormalizer.text(term);
            return text.contains(" " + t + " ") || text.contains(" " + t + "party ")
                    || text.contains(" " + t + "partys ") || text.contains(" " + t + "parties ");
        }).distinct().sorted().toList();
    }
}
