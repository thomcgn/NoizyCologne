package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.discovery.DiscoveryWorkLock;
import de.noizycologne.backend.editorial.CaptionPretextConfigService;
import de.noizycologne.backend.media.GenreLexicon;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Reset intent and database outcome survive process loss; only committed exact intents authorize files. */
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
        "editorial_caption_config", "editorial_post_draft_genres", "editorial_post_draft_mentions",
        "editorial_post_drafts", "event_cancellations", "event_change_history",
        "event_changes", "genre_aliases", "genres", "media_assets",
        "music_track_genres", "music_track_party_series", "music_tracks",
        "party_djs", "party_organizers", "party_series", "party_series_aliases",
        "party_series_genres", "venues"
    );
    private static final List<String> ORDERED_TABLES = APP_TABLES.stream().sorted().toList();

    private final JdbcTemplate jdbc;
    private final DiscoveryWorkLock discoveryWorkLock;
    private final MaintenanceGate gate;
    private final ResetJournal journal;
    private final GenreLexicon genreLexicon;

    public FactoryResetService(JdbcTemplate jdbc, DiscoveryWorkLock discoveryWorkLock,
                               MaintenanceGate gate, ResetJournal journal, GenreLexicon genreLexicon) {
        this.jdbc = jdbc; this.discoveryWorkLock = discoveryWorkLock;
        this.gate = gate; this.journal = journal; this.genreLexicon = genreLexicon;
    }

    public record TableCount(String name, long rows) { }
    public record Preview(List<@NonNull TableCount> tables, long recordCount, long mediaFileCount, String revision) { }
    public record Result(long deletedRecords, long deletedFiles, long filesNotDeleted, boolean partial, String message) { }
    private record State(long revision, @Nullable String generation, @Nullable String hash, long deletedRecords) { }

    public Preview preview() {
        try (var lease = resetLease()) {
            return transaction(lease.connection(), db -> {
                lockAndValidate(db);
                if (journal.exists()) throw conflict("Ein Reset-Journal wartet auf Prüfung. Bitte den Administrator informieren; keinen neuen Reset starten.");
                return Objects.requireNonNull(snapshot(db, inventory()));
            });
        } catch (SQLException exception) { throw unavailable("Discovery-Sperre konnte nicht freigegeben werden.", exception); }
    }

    public Result execute(String expectedRevision) {
        if (!expectedRevision.matches("[a-f0-9]{64}")) throw conflict("Die geprüfte Vorschau fehlt. Bitte neu öffnen.");
        Result result = null;
        ResetJournal.Prepared committed = null;
        try (var lease = resetLease()) {
            committed = prepareAndCommit(lease.connection(), expectedRevision);
            result = reconcile(lease.connection(), committed);
        } catch (SQLException | RuntimeException exception) {
            if (committed != null) {
                if (result == null) {
                    long total = committed.intent().entries().stream().filter(entry -> !entry.directory()).count();
                    long removed = committed.intent().entries().stream().filter(entry -> !entry.directory() && journal.missing(entry)).count();
                    result = new Result(committed.intent().deletedRecords(), removed, total - removed, true, "");
                }
                log.error("Reset committed; coordination could not be released safely", exception);
                return new Result(result.deletedRecords(), result.deletedFiles(), result.filesNotDeleted(), true,
                    "Datenbank-Reset ist bestätigt. Der technische Abschluss ist gestört. Bitte den Administrator informieren; keinen neuen Reset starten.");
            }
            if (exception instanceof ResponseStatusException status) throw status;
            throw unavailable("Reset-Ergebnis konnte nicht bestätigt werden. Bitte den Administrator informieren und den Zustand prüfen; keinen weiteren Reset anfordern.", exception);
        }
        return Objects.requireNonNull(result);
    }

    // Separate durable boundary is exercised by crash/restart fixtures, never exposed as an API.
    ResetJournal.Prepared prepareAndCommit(Connection connection, String expectedRevision) {
        return transaction(connection, db -> {
            lockAndValidate(db);
            if (journal.exists()) throw conflict("Ein früherer Reset muss zuerst sicher abgeschlossen werden.");
            ResetJournal.Inventory media = inventory();
            Preview before = snapshot(db, media);
            if (!before.revision().equals(expectedRevision))
                throw conflict("Daten oder Dateien haben sich seit der Vorschau geändert. Es wurde nichts gelöscht. Bitte die Vorschau erneut öffnen.");
            String generation = ResetJournal.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            ResetJournal.Prepared prepared;
            try { prepared = journal.prepare(new ResetJournal.Intent(1, generation, before.recordCount(), media.entries())); }
            catch (IOException exception) { throw unavailable("Reset-Journal konnte nicht sicher geschrieben werden. Kein Datenbank-Reset gestartet.", exception); }
            // Do not remove the journal on an exception: a lost commit response has an unknown outcome.
            String targets = ORDERED_TABLES.stream().map(name -> "public.\"" + name + "\"")
                .collect(java.util.stream.Collectors.joining(","));
            db.execute("TRUNCATE TABLE " + targets + " RESTART IDENTITY");
            // The shared caption config is application state too; after the reset it must exist again
            // with its documented default so the editorial UI keeps a valid configuration.
            db.update("INSERT INTO editorial_caption_config(singleton,pretext,revision) VALUES (true,?,1)",
                    CaptionPretextConfigService.DEFAULT_PRETEXT);
            db.update("UPDATE maintenance_state SET pending_reset=?,pending_manifest_sha256=?,deleted_records=? WHERE singleton",
                prepared.intent().generation(), prepared.sha256(), before.recordCount());
            return prepared;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            State initial = state(jdbc);
            if (initial.generation() == null && !journal.exists()) return;
            try (var lease = resetLease()) {
                State current = state(template(lease.connection()));
                ResetJournal.Prepared prepared = journal.read();
                String generation = current.generation();
                if (generation == null) {
                    if (prepared != null) journal.remove(prepared); // No committed intent: metadata only, NEVER media.
                    log.info("Reset recovery: uncommitted/completed journal reconciled; no application file changed");
                    return;
                }
                if (prepared == null || !generation.equals(prepared.intent().generation())
                        || !Objects.equals(current.hash(), prepared.sha256())
                        || current.deletedRecords() != prepared.intent().deletedRecords())
                    throw conflict("Bestätigter Reset und Dateijournal stimmen nicht überein. Keine Dateien entfernt.");
                Result result = reconcile(lease.connection(), prepared);
                if (result.partial()) log.error("Reset recovery is incomplete; {} media files retained", result.filesNotDeleted());
                else log.info("Reset recovery completed for a previously committed intent");
            }
        } catch (IOException | SQLException | RuntimeException exception) {
            gate.stop();
            log.error("Reset recovery failed closed; operator intervention required", exception);
        }
    }

    private Result reconcile(Connection connection, ResetJournal.Prepared prepared) {
        JdbcTemplate db = template(connection);
        State current = state(db);
        if (!prepared.intent().generation().equals(current.generation()) || !prepared.sha256().equals(current.hash()))
            throw conflict("Der bestätigte Reset-Zustand stimmt nicht mit dem Journal überein. Keine Dateien entfernt.");
        long total = prepared.intent().entries().stream().filter(entry -> !entry.directory()).count();
        genreLexicon.refresh(); // The confirmed empty DB must also replace the local OCR snapshot before reopening writes.
        Set<String> referenced = new HashSet<>();
        try {
            List<String> references = db.queryForList("SELECT storage_path FROM media_assets LIMIT 20001", String.class);
            if (references.size() > 20_000) throw new IOException("Media reference limit exceeded");
            for (String reference : references) referenced.add(journal.referenceKey(reference));
        } catch (IOException | RuntimeException exception) {
            log.error("Committed reset cannot safely verify media references; no further cleanup", exception);
            long removed = prepared.intent().entries().stream().filter(entry -> !entry.directory() && journal.missing(entry)).count();
            return new Result(prepared.intent().deletedRecords(), removed, total - removed, true,
                "Datenbank-Reset ist bestätigt. Medienreferenzen konnten nicht sicher geprüft werden. Dateien bleiben erhalten; Schreibzugriffe bleiben gesperrt.");
        }
        List<ResetJournal.@NonNull Entry> descending = new ArrayList<>(prepared.intent().entries());
        descending.sort(Comparator.comparingInt((ResetJournal.Entry entry) -> java.nio.file.Path.of(entry.path()).getNameCount()).reversed());
        long failures = 0;
        for (ResetJournal.Entry entry : descending) {
            if (!entry.directory() && referenced.contains(entry.path())) { failures++; continue; }
            try { journal.delete(entry); }
            catch (IOException exception) {
                failures++;
                if (failures <= 3) log.error("Reset cleanup retained a changed or inaccessible entry", exception);
            }
        }
        long removed = prepared.intent().entries().stream().filter(entry -> !entry.directory() && journal.missing(entry)).count();
        long remaining = total - removed;
        try {
            // Unlisted files are preserved, not swept into this already-confirmed operation.
            remaining = journal.inventory().files();
            if (failures > 0 || remaining > 0 || !journal.unchanged(prepared))
                return new Result(prepared.intent().deletedRecords(), removed, remaining, true,
                    "Datenbank-Reset ist bestätigt. Dateibereinigung ist unvollständig; geänderte, neue oder referenzierte Dateien bleiben erhalten. Schreibzugriffe bleiben bis zur sicheren Prüfung gesperrt.");
            int updated = db.update("UPDATE maintenance_state SET pending_reset=NULL,pending_manifest_sha256=NULL,deleted_records=0 WHERE singleton AND pending_reset=? AND pending_manifest_sha256=?",
                prepared.intent().generation(), prepared.sha256());
            if (updated != 1) throw conflict("Reset-Abschluss konnte nicht bestätigt werden.");
            journal.remove(prepared);
            return new Result(prepared.intent().deletedRecords(), total, 0, false,
                "Datenbank und bestätigte Mediendateien wurden zurückgesetzt.");
        } catch (IOException | RuntimeException exception) {
            log.error("Database reset committed; cleanup completion remains unconfirmed", exception);
            return new Result(prepared.intent().deletedRecords(), removed, remaining, true,
                "Datenbank-Reset ist bestätigt. Der sichere Abschluss der Bereinigung konnte nicht bestätigt werden. Bitte den Administrator informieren; keinen neuen Reset starten.");
        }
    }

    private void lockAndValidate(JdbcTemplate db) {
        Boolean available = db.queryForObject("SELECT pg_try_advisory_xact_lock(73624602)", Boolean.class);
        if (!Boolean.TRUE.equals(available)) throw conflict("Eine Kalenderänderung ist aktiv. Kein Reset gestartet.");
        db.execute("SET LOCAL lock_timeout='1500ms'");
        validateSchema(db);
        if (state(db).generation() != null)
            throw conflict("Ein bestätigter Reset wartet auf den sicheren Abschluss der Dateibereinigung. Kein neuer Reset gestartet.");
    }

    private Preview snapshot(JdbcTemplate db, ResetJournal.Inventory media) {
        List<@NonNull TableCount> tables = new ArrayList<>();
        long total = 0;
        for (String table : ORDERED_TABLES) {
            long rows = Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM public.\"" + table + "\"", Long.class));
            tables.add(new TableCount(table, rows)); total = Math.addExact(total, rows);
        }
        String fingerprint = state(db).revision() + "\n" + media.fingerprint() + "\n" + tables;
        return new Preview(List.copyOf(tables), total, media.files(), ResetJournal.digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
    }

    private static State state(JdbcTemplate db) {
        return Objects.requireNonNull(db.queryForObject("SELECT r.last_value,s.pending_reset,s.pending_manifest_sha256,s.deleted_records FROM maintenance_state s CROSS JOIN maintenance_revision r WHERE s.singleton",
            (result, row) -> new State(result.getLong(1), result.getString(2), result.getString(3), result.getLong(4))));
    }

    private void validateSchema(JdbcTemplate db) {
        Set<String> expected = new HashSet<>(APP_TABLES);
        expected.addAll(Set.of("flyway_schema_history", "auth_sessions", "auth_login_buckets", "maintenance_state"));
        if (!new HashSet<>(db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class)).equals(expected))
            throw conflict("Das Datenbankschema weicht vom freigegebenen Reset-Umfang ab. Es wurden keine Daten gelöscht.");
        for (boolean before : List.of(true, false)) {
            String name = before ? "noizy_mutation_guard" : "noizy_mutation_revision";
            String function = before ? "noizy_guard_mutation()" : "noizy_record_mutation()";
            Set<String> covered = new HashSet<>(db.queryForList("""
                SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND NOT t.tgisinternal AND t.tgenabled='O'
                  AND t.tgname=? AND t.tgfoid=CAST(? AS regprocedure) AND t.tgtype=?
                """, String.class, name, function, before ? 62 : 60));
            if (!covered.equals(APP_TABLES)) throw conflict("Mutationsschutz oder Datenrevision ist unvollständig. Kein Reset gestartet.");
        }
    }

    private ResetJournal.Inventory inventory() {
        try { return journal.inventory(); }
        catch (IOException exception) { throw unavailable("Medienbestand kann nicht sicher geprüft werden. Kein Reset gestartet.", exception); }
    }

    private record ResetLease(MaintenanceGate.Lease exclusive, DiscoveryWorkLock.Lease discovery) implements AutoCloseable {
        Connection connection() { return exclusive.connection(); }
        @Override public void close() throws SQLException {
            var held = discovery;
            try (held) { exclusive.close(); }
        }
    }

    private ResetLease resetLease() {
        // Check Discovery first: an active background scan must not encounter a transient exclusive mutation lock.
        DiscoveryWorkLock.Lease discovery = discoveryLease();
        try { return new ResetLease(gate.exclusive(), discovery); }
        catch (RuntimeException exception) {
            try { discovery.close(); }
            catch (SQLException closing) { exception.addSuppressed(closing); }
            throw exception;
        }
    }

    private DiscoveryWorkLock.Lease discoveryLease() {
        try {
            DiscoveryWorkLock.Lease lease = discoveryWorkLock.tryAcquire();
            if (lease == null) throw conflict("Ein Discovery-Lauf ist aktiv. Kein Reset gestartet.");
            return lease;
        } catch (SQLException exception) { throw unavailable("Discovery-Sperre ist nicht verfügbar.", exception); }
    }

    private static JdbcTemplate template(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(Objects.requireNonNull(connection), true));
    }

    private static <T extends @NonNull Object> T transaction(Connection connection, Function<JdbcTemplate,T> work) {
        var source = new SingleConnectionDataSource(Objects.requireNonNull(connection), true);
        var transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        return Objects.requireNonNull(transactions.execute(status -> work.apply(new JdbcTemplate(source))));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    private static ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
