package de.noizycologne.backend.genre;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GenreImportService {
    private final GenreRepository genres;
    private final GenreAliasRepository aliases;
    private final GenreService service;
    public GenreImportService(GenreRepository genres, GenreAliasRepository aliases, GenreService service) {
        this.genres = genres; this.aliases = aliases; this.service = service;
    }

    public record Issue(int line, String genre, String field, String message) { }
    public record Item(int line, String canonicalName, String displayName, boolean active, List<@NonNull String> aliases,
                       Map<String, String> metadata, String action) { }
    public record Report(int total, int created, int unchanged, int supplemented, int inactive, int aliases,
                         int aliasesAdded, boolean valid, boolean applied, List<Item> rows, List<Issue> conflicts) { }
    private record Change(GenreImportCatalog.Row row, @Nullable Genre existing, List<@NonNull String> missingAliases) { }

    /** Locks are short-lived, shared with CRUD, and contain no filesystem or network work. */
    @Transactional
    public Report evaluate(List<GenreImportCatalog.@NonNull Row> rows, boolean apply) {
        service.lockCatalogue();
        var existingGenres = genres.findAllByOrderByCanonicalNameAsc();
        var existingAliases = aliases.findAllByOrderByValueAsc();
        Map<String, List<Genre>> byName = new HashMap<>();
        Map<String, Set<String>> owners = new HashMap<>();
        Map<String, Set<String>> aliasNames = new HashMap<>();
        for (Genre genre : existingGenres) {
            byName.computeIfAbsent(key(genre.getCanonicalName()), ignored -> new ArrayList<>()).add(genre);
            own(owners, genre.getCanonicalName(), genre.getId());
            own(owners, genre.getDisplayName(), genre.getId());
        }
        for (GenreAlias alias : existingAliases) {
            own(owners, alias.getValue(), alias.getGenreId());
            aliasNames.computeIfAbsent(alias.getGenreId(), ignored -> new HashSet<>()).add(key(alias.getValue()));
        }
        List<Issue> issues = new ArrayList<>();
        Map<String, String> incomingOwners = new HashMap<>();
        Set<String> canonicals = new HashSet<>();
        List<Change> changes = new ArrayList<>();
        for (var row : rows) {
            String canonical = key(row.canonicalName());
            if (!canonicals.add(canonical)) issue(issues, row, "canonicalName", "Doppelter kanonischer Name");
            var matches = byName.getOrDefault(canonical, List.of());
            Genre existing = matches.size() == 1 ? matches.getFirst() : null;
            if (matches.size() > 1) issue(issues, row, "canonicalName", "Mehrdeutige vorhandene Genres");
            if (existing != null && (!existing.getCanonicalName().equals(row.canonicalName())
                    || !existing.getDisplayName().equals(row.displayName()) || existing.isActive() != row.active()))
                issue(issues, row, "canonicalName/displayName/active", "Vorhandene Werte weichen ab; manuelle Daten bleiben geschützt");
            String owner = existing == null ? "incoming:" + canonical : existing.getId();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(row.canonicalName(), "canonicalName");
            fields.putIfAbsent(row.displayName(), "displayName");
            Set<String> uniqueAliases = new HashSet<>();
            for (String alias : row.aliases()) {
                if (!uniqueAliases.add(key(alias))) issue(issues, row, "aliases", "Doppelter Alias: " + alias);
                fields.putIfAbsent(alias, "aliases");
            }
            for (var field : fields.entrySet()) {
                String normalized = key(field.getKey());
                if (owners.getOrDefault(normalized, Set.of()).stream().anyMatch(id -> !id.equals(owner)))
                    issue(issues, row, field.getValue(), "Name/Alias bereits einem anderen Genre zugeordnet: " + field.getKey());
                String prior = incomingOwners.putIfAbsent(normalized, owner);
                if (prior != null && !prior.equals(owner)) issue(issues, row, field.getValue(), "Name/Alias im Import mehrdeutig: " + field.getKey());
            }
            Set<String> knownAliases = existing == null ? Set.of() : aliasNames.getOrDefault(existing.getId(), Set.of());
            changes.add(new Change(row, existing, row.aliases().stream().filter(alias -> !knownAliases.contains(key(alias))).toList()));
        }
        int created = 0, unchanged = 0, supplemented = 0, added = 0;
        List<Item> items = new ArrayList<>();
        for (Change change : changes) {
            var row = change.row();
            String action;
            if (change.existing() == null) { created++; action = "NEU"; }
            else if (change.missingAliases().isEmpty()) { unchanged++; action = "UNVERÄNDERT"; }
            else { supplemented++; action = "ALIASE ERGÄNZEN"; }
            added += change.missingAliases().size();
            items.add(new Item(row.line(), row.canonicalName(), row.displayName(), row.active(), row.aliases(), row.metadata(), action));
        }
        if (apply && issues.isEmpty() && (created > 0 || added > 0)) {
            for (Change change : changes) {
                var row = change.row();
                Genre genre = change.existing();
                if (genre == null) genre = genres.saveAndFlush(new Genre(row.canonicalName(), row.displayName(), row.active()));
                String id = genre.getId();
                for (String alias : change.missingAliases()) aliases.save(new GenreAlias(id, alias));
            }
            aliases.flush();
            service.refreshLexiconAfterCommit();
        }
        return new Report(rows.size(), created, unchanged, supplemented, (int) rows.stream().filter(row -> !row.active()).count(),
                rows.stream().mapToInt(row -> row.aliases().size()).sum(), added, issues.isEmpty(), apply && issues.isEmpty(),
                List.copyOf(items), List.copyOf(issues));
    }
    private static String key(String text) { return GenreNormalizer.normalize(text); }
    private static void own(Map<String, Set<String>> owners, String name, String id) {
        owners.computeIfAbsent(key(name), ignored -> new HashSet<>()).add(id);
    }
    private static void issue(List<Issue> issues, GenreImportCatalog.Row row, String field, String message) {
        issues.add(new Issue(row.line(), row.canonicalName(), field, message));
    }
}
