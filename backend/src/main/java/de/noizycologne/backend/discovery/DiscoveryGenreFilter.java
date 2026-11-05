package de.noizycologne.backend.discovery;

import de.noizycologne.backend.genre.*;
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
    static boolean matches(EventExtractor.Event event, Collection<String> terms) {
        if (PartyOnlyPolicy.concert(event)) return false;
        String text = " " + EventNormalizer.text(event.title() + " " + event.evidence()) + " ";
        return terms.stream().map(EventNormalizer::text).filter(t -> !t.isBlank())
                .anyMatch(t -> text.contains(" " + t + " ") || text.contains(" " + t + "party ")
                        || text.contains(" " + t + "partys ") || text.contains(" " + t + "parties "));
    }
}
