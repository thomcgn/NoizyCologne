package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.discovery.DiscoveryWorkLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="noizy.api.trust.secret=isolated-proxy-fixture-key-not-a-production-secret")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "noizy.media.root=target/test-factory-reset-media",
    "noizy.factory-reset.secret=fixture-factory-reset-secret-very-long-for-testing",
    "noizy.discovery.enabled=false",
    "noizy.discovery.monitor.enabled=false"
})
@TestMethodOrder(MethodOrderer.MethodName.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FactoryResetIntegrationTests {
    private static final String SECRET = "fixture-factory-reset-secret-very-long-for-testing";
    private static final Path MEDIA = Path.of("target/test-factory-reset-media");
    // This class intentionally wipes all app tables. Never run that test against
    // the shared CI fixture DB (or any non-CI database).
    private static String originalUrl;
    private static String isolatedName;
    private static String databaseUser;
    private static String databasePassword;

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) throws Exception {
        originalUrl = java.util.Objects.requireNonNull(System.getenv("DB_URL"), "CI test DB_URL is required");
        databaseUser = java.util.Objects.requireNonNull(System.getenv("DB_USER"), "CI DB_USER is required");
        databasePassword = java.util.Objects.requireNonNull(System.getenv("DB_PASSWORD"), "CI DB_PASSWORD is required");
        if (!originalUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{2,5}/noizy_quality")
                || !"noizy_quality".equals(databaseUser) || databasePassword.length() < 16)
            throw new IllegalStateException("Destructive integration test requires the isolated noizy_quality CI fixture database");
        isolatedName = "noizy_factory_reset_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(originalUrl, databaseUser, databasePassword);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + isolatedName);
        }
        String url = originalUrl.substring(0, originalUrl.lastIndexOf('/') + 1) + isolatedName;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
    }

    @AfterAll
    static void dropIsolatedPostgres() throws Exception {
        if (originalUrl == null || isolatedName == null) return;
        try (Connection connection = DriverManager.getConnection(originalUrl, databaseUser, databasePassword);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + isolatedName + " WITH (FORCE)");
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DiscoveryWorkLock discoveryLock;
    @Autowired MaintenanceGate gate;
    @Autowired ResetJournal journal;
    @Autowired FactoryResetService service;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired de.noizycologne.backend.media.GenreLexicon lexicon;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean de.noizycologne.backend.calendar.CalendarEntryService calendarService;
    private String venueId;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(MEDIA);
        venueId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues(id,name,instagram) VALUES (?,?,?)",
            venueId, "Factory Fixture " + venueId, "");
    }

    @AfterEach void cleanup() throws Exception {
        // This is the class-owned disposable database, never an operator cleanup instruction.
        jdbc.update("UPDATE maintenance_state SET pending_reset=NULL,pending_manifest_sha256=NULL,deleted_records=0 WHERE singleton");
        org.mockito.Mockito.reset(calendarService);
        jdbc.execute("DELETE FROM calendar_entries");
        jdbc.execute("DELETE FROM party_series");
        jdbc.execute("DELETE FROM media_assets");
        jdbc.update("DELETE FROM venues WHERE id=?", venueId);
        if (Files.isDirectory(MEDIA)) {
            try (var files = Files.walk(MEDIA)) {
                for (var path : files.sorted((a,b) -> Integer.compare(b.getNameCount(),a.getNameCount())).toList()) {
                    if (Files.exists(path)) Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test void a_invalidOrMissingServiceSecretCannotPreviewOrReset() throws Exception {
        mvc.perform(get("/internal/maintenance/factory-reset"))
            .andExpect(status().isForbidden());
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                .header("X-Noizy-Reset-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phrase\":\"Kompletter Reset\"}")))
            .andExpect(status().isForbidden());
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                .header("X-Noizy-Reset-Key", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phrase\":\"RESET\"}")))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1L);
    }

    @Test void b_previewIsReadOnlyAndReportsAllMediaAndDatabaseRows() throws Exception {
        Files.writeString(MEDIA.resolve("poster.jpg"), "fixture");
        mvc.perform(java.util.Objects.requireNonNull(get("/internal/maintenance/factory-reset")
                .header("X-Noizy-Reset-Key", SECRET)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordCount").isNumber())
            .andExpect(jsonPath("$.mediaFileCount").value(1));
        assertThat(Files.exists(MEDIA.resolve("poster.jpg"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1L);
    }

    private String revision() throws Exception {
        var body = mvc.perform(java.util.Objects.requireNonNull(get("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key", SECRET)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return java.util.Objects.requireNonNull(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)).path("revision").asString();
    }
    private @org.jspecify.annotations.NonNull String confirmed(String revision) { return "{\"phrase\":\"Kompletter Reset\",\"revision\":\"" + revision + "\"}"; }

    @Test void c_activeDiscoveryLockPreventsDestructiveChanges() throws Exception {
        Files.writeString(MEDIA.resolve("protected.png"), "fixture");
        String revision = revision();
        try (var lease = discoveryLock.tryAcquire()) {
            assertThat(lease).isNotNull();
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                    .header("X-Noizy-Reset-Key", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmed(revision))))
                .andExpect(status().isConflict());
        }
        assertThat(Files.exists(MEDIA.resolve("protected.png"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1L);
    }

    @Test void d_schemaDriftFailsClosedWithoutDeletingAnything() throws Exception {
        String revision = revision();
        jdbc.execute("CREATE TABLE factory_reset_unexpected_fixture(id integer)");
        try {
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                    .header("X-Noizy-Reset-Key", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmed(revision))))
                .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1L);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS factory_reset_unexpected_fixture");
        }
    }

    @Test void e_resetRemovesAllApplicationRowsAndNestedMediaButPreservesFlywayAndLoginInfrastructure() throws Exception {
        Files.writeString(MEDIA.resolve("party.jpg"), "party");
        Path nested = MEDIA.resolve("flyers");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("event.png"), "event");
        String nonce = "a".repeat(64), generation = "b".repeat(64), bucket = "c".repeat(64);
        jdbc.update("INSERT INTO auth_sessions(nonce_hash,generation,expires_at,revoked) VALUES (?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour',true)", nonce, generation);
        jdbc.update("INSERT INTO auth_login_buckets(bucket_hash,attempts,expires_at) VALUES (?,5,CURRENT_TIMESTAMP+INTERVAL '15 minutes')", bucket);
        long migrations = java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class));
        String revision = revision();
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                .header("X-Noizy-Reset-Key", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmed(revision))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedFiles").value(2))
            .andExpect(jsonPath("$.partial").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues",Long.class)).isZero();
        assertThat(lexicon.findGenres(java.util.List.<de.noizycologne.backend.media.TesseractOcrService.OcrWord>of(
            new de.noizycologne.backend.media.TesseractOcrService.OcrWord("Rock",95,0,0,10,10,"fixture",11,1,1,1)))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class)).isEqualTo(migrations);
        assertThat(jdbc.queryForObject("SELECT revoked FROM auth_sessions WHERE nonce_hash=?", Boolean.class, nonce)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_login_buckets WHERE bucket_hash=?", Integer.class, bucket)).isEqualTo(5);
        assertThat(Files.isDirectory(MEDIA)).isTrue();
        try (var files = Files.list(MEDIA)) { assertThat(files.count()).isZero(); }
    }
    @Test void f_sameRowCountMutationAndChangedMediaInvalidateTheCheckedRevision() throws Exception {
        Files.writeString(MEDIA.resolve("old.png"),"old");
        String old=revision();
        jdbc.update("UPDATE venues SET name=? WHERE id=?","Changed fixture",venueId);
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
            .contentType(MediaType.APPLICATION_JSON).content(confirmed(old)))).andExpect(status().isConflict());
        String next=revision(); var modified=Files.getLastModifiedTime(MEDIA.resolve("old.png"));
        Files.writeString(MEDIA.resolve("old.png"),"new"); Files.setLastModifiedTime(MEDIA.resolve("old.png"),modified);
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
            .contentType(MediaType.APPLICATION_JSON).content(confirmed(next)))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1);
        assertThat(Files.readString(MEDIA.resolve("old.png"))).isEqualTo("new"); assertThat(journal.exists()).isFalse();
    }
    @Test void g_missingRevisionSymlinkAndDisabledMutationGuardCannotReset() throws Exception {
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
            .contentType(MediaType.APPLICATION_JSON).content("{\"phrase\":\"Kompletter Reset\"}"))).andExpect(status().isConflict());
        String checked=revision();
        Path link=MEDIA.resolve("link"); Files.createSymbolicLink(link,MEDIA.toAbsolutePath());
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
            .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isServiceUnavailable());
        Files.delete(link);
        jdbc.execute("ALTER TABLE genres DISABLE TRIGGER noizy_mutation_guard");
        try {
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isConflict());
            assertThat(journal.exists()).isFalse();
        } finally { jdbc.execute("ALTER TABLE genres ENABLE TRIGGER noizy_mutation_guard"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1);
    }
    @Test void h_sharedRequestAndDatabaseMutationLocksExcludeResetAcrossGateInstances() throws Exception {
        String checked=revision();
        try(var active=gate.enter()) {
            assertThat(active).isNotNull();
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isConflict());
            var other=new MaintenanceGate(dataSource);
            org.assertj.core.api.Assertions.assertThatThrownBy(other::exclusive).hasMessageContaining("aktiv");
        }
        try(var exclusive=gate.exclusive()) {
            assertThat(exclusive.connection().isClosed()).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE venues SET name='blocked' WHERE id=?",venueId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT name FROM venues WHERE id=?",String.class,venueId)).startsWith("Factory Fixture");
    }
    @Test void i_realJvmCrashAfterCommitRecoversFilesOnNextProcessStartup() throws Exception {
        Files.writeString(MEDIA.resolve("crash.png"),"old");
        runFixture("commit-and-halt",17);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues",Long.class)).isZero();
        assertThat(Boolean.TRUE.equals(jdbc.queryForObject("SELECT pending_reset IS NOT NULL FROM maintenance_state WHERE singleton",Boolean.class))).isTrue();
        assertThat(Files.readString(MEDIA.resolve("crash.png"))).isEqualTo("old"); assertThat(journal.exists()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(gate::enter).hasMessageContaining("bestätigter Reset");
        runFixture("recover",0);
        assertThat(Files.exists(MEDIA.resolve("crash.png"))).isFalse(); assertThat(journal.exists()).isFalse();
        try(var active=gate.enter()) { assertThat(active).isNotNull(); }
    }

    @Test void j_realUploadIsProtectedUntilItsFileIoAndDatabaseCommitHaveCompleted() throws Exception {
        String party=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO party_series(id,name,instagram,color) VALUES (?,?,?,?)",party,"Upload fixture","","#123456");
        Files.writeString(MEDIA.resolve("protected.png"),"old");
        String checked=revision();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var upload=new org.springframework.mock.web.MockMultipartFile("file","fixture.png","image/png",png) {
            @Override public java.io.@org.jspecify.annotations.NonNull InputStream getInputStream() throws java.io.IOException {
                if(reads.incrementAndGet()==2) {
                    entered.countDown();
                    try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Fixture upload timed out"); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException(exception); }
                }
                return super.getInputStream();
            }
        };
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var request=executor.submit(() -> mvc.perform(java.util.Objects.requireNonNull(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/parties/{id}/default-flyer",party).file(upload))).andExpect(status().isOk()));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isConflict());
                assertThat(journal.exists()).isFalse(); assertThat(Files.readString(MEDIA.resolve("protected.png"))).isEqualTo("old");
            } finally { release.countDown(); }
            request.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT default_flyer_media_id IS NOT NULL FROM party_series WHERE id=?",Boolean.class,party)).isTrue();
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
            .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isConflict());
    }
    @Test void k_calendarPostIsProtectedForTheEntireControllerServiceOperation() throws Exception {
        String party=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO party_series(id,name,instagram,color) VALUES (?,?,?,?)",party,"Calendar fixture","","#123456");
        String checked=revision();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Calendar fixture timed out");
            return invocation.callRealMethod();
        }).when(calendarService).createEntry(org.mockito.ArgumentMatchers.any(de.noizycologne.backend.calendar.CalendarEntryRequest.class));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var request=executor.submit(() -> mvc.perform(java.util.Objects.requireNonNull(post("/api/v1/calendar-entries")
                .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-11-14\",\"locationId\":\""+venueId+"\",\"type\":\"event\",\"partyId\":\""+party+"\",\"notes\":\"Fixture\",\"startTime\":\"23:00\"}"))).andExpect(status().isCreated()));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset").header("X-Noizy-Reset-Key",SECRET)
                    .contentType(MediaType.APPLICATION_JSON).content(confirmed(checked)))).andExpect(status().isConflict());
                assertThat(journal.exists()).isFalse();
            } finally { release.countDown(); }
            request.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Long.class,venueId)).isEqualTo(1);
    }
    @Test void l_recoveryPreservesReplacedAndNewFilesAndKeepsWritesBlocked() throws Exception {
        Files.writeString(MEDIA.resolve("old.png"),"old");
        commitWithoutCleanup();
        Files.delete(MEDIA.resolve("old.png"));
        Files.writeString(MEDIA.resolve("old.png"),"replacement");
        Files.writeString(MEDIA.resolve("new.png"),"new");
        var recoveryGate=new MaintenanceGate(dataSource);
        new FactoryResetService(jdbc,discoveryLock,recoveryGate,journal,lexicon).recoverOnStartup();
        assertThat(Files.readString(MEDIA.resolve("old.png"))).isEqualTo("replacement");
        assertThat(Files.readString(MEDIA.resolve("new.png"))).isEqualTo("new");
        assertThat(journal.exists()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(recoveryGate::enter).hasMessageContaining("bestätigter Reset");
    }

    @Test void m_missingOrTamperedCommittedJournalFailsClosedWithoutDeletingFiles() throws Exception {
        Files.writeString(MEDIA.resolve("old.png"),"old");
        commitWithoutCleanup();
        Path manifest=MEDIA.resolve(".noizy-reset.json");
        byte[] original=Files.readAllBytes(manifest);
        Files.delete(manifest);
        var missingGate=new MaintenanceGate(dataSource);
        new FactoryResetService(jdbc,discoveryLock,missingGate,journal,lexicon).recoverOnStartup();
        org.assertj.core.api.Assertions.assertThatThrownBy(missingGate::enter).hasMessageContaining("gestört");
        Files.write(manifest,original);
        Files.setPosixFilePermissions(manifest,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.writeString(manifest," ",java.nio.file.StandardOpenOption.APPEND);
        var changedGate=new MaintenanceGate(dataSource);
        new FactoryResetService(jdbc,discoveryLock,changedGate,journal,lexicon).recoverOnStartup();
        org.assertj.core.api.Assertions.assertThatThrownBy(changedGate::enter).hasMessageContaining("gestört");
        assertThat(Files.readString(MEDIA.resolve("old.png"))).isEqualTo("old");
    }

    @Test void n_uncommittedOrCompletedJournalOnlyRemovesMetadataAndPreservesNewData() throws Exception {
        Files.writeString(MEDIA.resolve("old.png"),"old");
        var inventory=journal.inventory();
        journal.prepare(new ResetJournal.Intent(1,"a".repeat(64),0,inventory.entries()));
        Files.writeString(MEDIA.resolve("new.png"),"new");
        var recoveryGate=new MaintenanceGate(dataSource);
        new FactoryResetService(jdbc,discoveryLock,recoveryGate,journal,lexicon).recoverOnStartup();
        assertThat(journal.exists()).isFalse();
        assertThat(Files.readString(MEDIA.resolve("old.png"))).isEqualTo("old");
        assertThat(Files.readString(MEDIA.resolve("new.png"))).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1);
        try(var available=recoveryGate.enter()) { assertThat(available).isNotNull(); }
    }

    @Test void o_recoveryProtectsCanonicalizedReferencesIntroducedByAnExternalRestore() throws Exception {
        Files.writeString(MEDIA.resolve("old.png"),"old");
        var prepared=commitWithoutCleanup();
        // Simulate an external restore only in this class-owned disposable database.
        // The temporary marker change and restored row commit atomically under the exclusive lease.
        try(var held=gate.exclusive()) {
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(java.util.Objects.requireNonNull(held.connection()),true);
            var transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(source));
            transactions.executeWithoutResult(status -> {
                var db=new JdbcTemplate(source);
                db.update("UPDATE maintenance_state SET pending_reset=NULL,pending_manifest_sha256=NULL WHERE singleton");
                db.update("INSERT INTO media_assets(id,storage_path,original_filename,media_type,file_size,checksum_sha256) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),"folder/../old.png","fixture.png","image/png",3,"a".repeat(64));
                db.update("UPDATE maintenance_state SET pending_reset=?,pending_manifest_sha256=? WHERE singleton",prepared.intent().generation(),prepared.sha256());
            });
        }
        var recoveryGate=new MaintenanceGate(dataSource);
        new FactoryResetService(jdbc,discoveryLock,recoveryGate,journal,lexicon).recoverOnStartup();
        assertThat(Files.readString(MEDIA.resolve("old.png"))).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets",Long.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(recoveryGate::enter).hasMessageContaining("bestätigter Reset");
    }

    private ResetJournal.Prepared commitWithoutCleanup() {
        String checked=service.preview().revision();
        try(var held=gate.exclusive()) { return service.prepareAndCommit(held.connection(),checked); }
    }

    private void runFixture(String mode,int expectedExit) throws Exception {
        String ownedUrl=originalUrl.substring(0,originalUrl.lastIndexOf('/')+1)+isolatedName;
        String classpath=java.util.Objects.requireNonNullElse(System.getProperty("surefire.test.class.path"),System.getProperty("java.class.path",""));
        Path output=Files.createTempFile(Path.of("target"),"reset-crash-",".log");
        Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,
            ResetCrashFixture.class.getName(),mode,MEDIA.toAbsolutePath().normalize().toString());
        for(String key:java.util.List.of("DB_URL","SPRING_DATASOURCE_URL","SPRING_FLYWAY_URL")) builder.environment().put(key,ownedUrl);
        for(String key:java.util.List.of("DB_USER","SPRING_DATASOURCE_USERNAME","SPRING_FLYWAY_USER")) builder.environment().put(key,databaseUser);
        for(String key:java.util.List.of("DB_PASSWORD","SPRING_DATASOURCE_PASSWORD","SPRING_FLYWAY_PASSWORD")) builder.environment().put(key,databasePassword);
        var child=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.Objects.requireNonNull(assertThat(child.exitValue()).withFailMessage("Crash fixture failed: %s",Files.readString(output))).isEqualTo(expectedExit);
        } finally {
            if(child.isAlive()) child.destroyForcibly().waitFor(10,java.util.concurrent.TimeUnit.SECONDS);
            Files.deleteIfExists(output);
        }
    }

}
