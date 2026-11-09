package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.discovery.DiscoveryWorkLock;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Destructive application-data reset. Never drops schemas, databases, Docker volumes,
 * credentials, or Flyway history. The backend itself is only reachable through the
 * authenticated frontend and still requires a separate internal service secret.
 */
@Service
public class FactoryResetService {
    private static final Logger log = LoggerFactory.getLogger(FactoryResetService.class);
    private static final Set<String> APP_TABLES = Set.of(
        "actor_collective_memberships", "actor_roles", "actors", "calendar_entries",
        "calendar_entry_mentions", "calendar_recurrence_occurrences", "cancellation_history",
        "discovery_candidate_identities", "discovery_candidates", "discovery_decisions",
        "discovery_geo_events", "discovery_keyword_rules", "discovery_observations",
        "discovery_operations", "discovery_place_aliases", "discovery_runs",
        "discovery_source_checks", "discovery_sources", "discovery_venue_geography",
        "editorial_post_draft_genres", "editorial_post_draft_mentions",
        "editorial_post_drafts", "event_cancellations", "event_change_history",
        "event_changes", "genre_aliases", "genres", "media_assets",
        "music_track_genres", "music_track_party_series", "music_tracks",
        "party_djs", "party_organizers", "party_series", "party_series_aliases",
        "party_series_genres", "venues"
    );
    private static final List<String> ORDERED_TABLES = APP_TABLES.stream().sorted().toList();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DiscoveryWorkLock discoveryWorkLock;
    private final Path mediaRoot;
    private final AtomicBoolean resetting = new AtomicBoolean();

    public FactoryResetService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               DiscoveryWorkLock discoveryWorkLock,
                               @Value("${noizy.media.root:./uploads}") String mediaRoot) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.discoveryWorkLock = discoveryWorkLock;
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    public record TableCount(String name, long rows) { }
    public record Preview(List<@NonNull TableCount> tables, long recordCount, long mediaFileCount) { }
    public record Result(long deletedRecords, long deletedFiles, long filesNotDeleted, boolean partial) { }
    private record MediaInventory(List<@NonNull Path> paths, long files) { }

    public Preview preview() {
        validateSchema();
        MediaInventory inventory = inventory();
        return counts(inventory.files());
    }

    public Result execute() {
        if (!resetting.compareAndSet(false, true))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ein Werksreset läuft bereits.");
        try {
            DiscoveryWorkLock.Lease lease;
            try {
                lease = discoveryWorkLock.tryAcquire();
            } catch (SQLException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Discovery-Sperre ist nicht verfügbar.", exception);
            }
            if (lease == null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ein Discovery-Lauf ist aktiv. Der Werksreset wurde nicht gestartet.");
            try (lease) {
                // Snapshot the files BEFORE the database transaction, so a concurrent new
                // upload can never be deleted merely because it appeared later.
                MediaInventory media = inventory();
                Preview before = Objects.requireNonNull(transactions.execute(status -> {
                    Boolean available = jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(73624602)", Boolean.class);
                    if (!Boolean.TRUE.equals(available))
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Die Datenbank wird gerade bearbeitet. Der Werksreset wurde nicht gestartet.");
                    validateSchema();
                    String targets = ORDERED_TABLES.stream()
                        .map(name -> "public.\"" + name + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
                    // Serialize concurrent writes for an accurate final row count.
                    jdbc.execute("SET LOCAL lock_timeout = '5s'");
                    jdbc.execute("LOCK TABLE " + targets + " IN ACCESS EXCLUSIVE MODE");
                    Preview snapshot = counts(media.files());
                    // Never CASCADE into an unknown table from another schema.
                    jdbc.execute("TRUNCATE TABLE " + targets + " RESTART IDENTITY");
                    return snapshot;
                }), "Reset-Transaktion lieferte kein Ergebnis");

                // Never delete media until PostgreSQL committed successfully.
                long removed = 0;
                long failed = 0;
                for (Path path : media.paths()) {
                    try {
                        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link");
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            if (Files.deleteIfExists(path)) removed++;
                        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException exception) {
                        failed++; // A non-empty directory is also an incomplete cleanup.
                        log.error("Factory reset: media cleanup could not remove an entry", exception);
                    }
                }
                boolean partial = failed > 0;
                if (partial) log.error("Factory reset committed; {} media entries remain", failed);
                else log.info("Factory reset completed: {} database rows and {} media files removed",
                    before.recordCount(), removed);
                return new Result(before.recordCount(), removed, failed, partial);
            } catch (SQLException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Discovery-Sperre konnte nicht freigegeben werden.", exception);
            }
        } finally {
            resetting.set(false);
        }
    }

    private Preview counts(long files) {
        List<@NonNull TableCount> tables = new ArrayList<>();
        long total = 0;
        for (String table : ORDERED_TABLES) {
            long count = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM public.\"" + table + "\"", Long.class));
            tables.add(new TableCount(table, count));
            total += count;
        }
        return new Preview(List.copyOf(tables), total, files);
    }

    private void validateSchema() {
        List<String> actual = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class);
        Set<String> expected = new HashSet<>(APP_TABLES);
        expected.addAll(Set.of("flyway_schema_history", "auth_sessions", "auth_login_buckets"));
        if (!new HashSet<>(actual).equals(expected))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Das Datenbankschema weicht vom freigegebenen Reset-Umfang ab. Es wurden keine Daten gelöscht.");
    }

    private MediaInventory inventory() {
        if (mediaRoot.getNameCount() < 2
            || !Files.isDirectory(mediaRoot, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(mediaRoot) || !Files.isWritable(mediaRoot))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Das Medienverzeichnis ist nicht sicher eingebunden oder nicht beschreibbar. Kein Reset.");
        try (Stream<Path> walk = Files.walk(mediaRoot)) {
            List<@NonNull Path> entries = new ArrayList<>();
            walk.filter(path -> !path.equals(mediaRoot))
                .forEach(path -> entries.add(Objects.requireNonNull(path)));
            for (Path path : entries) {
                if (!path.normalize().startsWith(mediaRoot) || Files.isSymbolicLink(path))
                    throw new IOException("Unsicherer Medienpfad");
            }
            long files = entries.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).count();
            List<@NonNull Path> descending = new ArrayList<>(entries);
            descending.sort((first, second) -> Integer.compare(second.getNameCount(), first.getNameCount()));
            return new MediaInventory(List.copyOf(descending), files);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Medienverzeichnis kann nicht sicher geprüft werden. Kein Reset.", exception);
        }
    }
}
