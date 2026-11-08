package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.discovery.DiscoveryWorkLock;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "noizy.media.root=target/test-factory-reset-media",
    "noizy.factory-reset.secret=fixture-factory-reset-secret-very-long-for-testing",
    "noizy.discovery.enabled=false",
    "noizy.discovery.monitor.enabled=false"
})
@TestMethodOrder(MethodOrderer.MethodName.class)
class FactoryResetIntegrationTests {
    private static final String SECRET = "fixture-factory-reset-secret-very-long-for-testing";
    private static final Path MEDIA = Path.of("target/test-factory-reset-media");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DiscoveryWorkLock discoveryLock;
    private String venueId;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(MEDIA);
        venueId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues(id,name,instagram) VALUES (?,?,?)",
            venueId, "Factory Fixture " + venueId, "");
    }

    @AfterEach void cleanup() throws Exception {
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

    @Test void c_activeDiscoveryLockPreventsDestructiveChanges() throws Exception {
        Files.writeString(MEDIA.resolve("protected.png"), "fixture");
        try (var lease = discoveryLock.tryAcquire()) {
            assertThat(lease).isNotNull();
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                    .header("X-Noizy-Reset-Key", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phrase\":\"Kompletter Reset\"}")))
                .andExpect(status().isConflict());
        }
        assertThat(Files.exists(MEDIA.resolve("protected.png"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues WHERE id=?",Long.class,venueId)).isEqualTo(1L);
    }

    @Test void d_schemaDriftFailsClosedWithoutDeletingAnything() throws Exception {
        jdbc.execute("CREATE TABLE factory_reset_unexpected_fixture(id integer)");
        try {
            mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                    .header("X-Noizy-Reset-Key", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phrase\":\"Kompletter Reset\"}")))
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
        long migrations = java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class));
        mvc.perform(java.util.Objects.requireNonNull(post("/internal/maintenance/factory-reset")
                .header("X-Noizy-Reset-Key", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phrase\":\"Kompletter Reset\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedFiles").value(2))
            .andExpect(jsonPath("$.partial").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM venues",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class)).isEqualTo(migrations);
        assertThat(Files.isDirectory(MEDIA)).isTrue();
        try (var files = Files.list(MEDIA)) { assertThat(files.count()).isZero(); }
    }
}
