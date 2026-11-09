package de.noizycologne.backend.genre;

import de.noizycologne.backend.media.GenreLexicon;
import de.noizycologne.backend.media.TesseractOcrService;
import de.noizycologne.backend.discovery.DiscoveryGenreFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"noizy.api.trust.secret=isolated-proxy-fixture-key-not-a-production-secret", "noizy.discovery.enabled=false", "noizy.discovery.monitor.enabled=false"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenreImportIntegrationTests {
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
            throw new IllegalStateException("Empty-catalog integration test requires the isolated noizy_quality CI fixture database");
        isolatedName = "noizy_genre_import_" + UUID.randomUUID().toString().replace("-", "");
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
    @Autowired GenreRepository genres;
    @Autowired GenreAliasRepository aliases;
    @Autowired GenreImportService importer;
    @Autowired DiscoveryGenreFilter discovery;
    @MockitoSpyBean GenreLexicon lexicon;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    @BeforeEach void emptyOwnedDatabase() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_import_alias ON genre_aliases");
        jdbc.execute("DELETE FROM genre_aliases");
        jdbc.execute("DELETE FROM genres");
        lexicon.refresh();
        clearInvocations(lexicon);
    }
    private byte[] catalogue() throws Exception {
        try (var input = getClass().getResourceAsStream("/genres/catalog-2026-11-09.csv")) {
            return json.writeValueAsBytes(Map.of("schemaVersion", 1, "csv", new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)));
        }
    }
    private byte[] payload(String canonical, String display, boolean active, String... values) {
        return json.writeValueAsBytes(Map.of("schemaVersion",1,"genres",List.of(Map.of("canonicalName",canonical,"displayName",display,"active",active,"aliases",List.of(values)))));
    }
    private GenreImportService.Report run(byte[] body, boolean apply) {
        return importer.evaluate(GenreImportCatalog.parse(body), apply);
    }
    @Test void previewDoesNotWriteAndConfirmedCsvImportIsAtomicIdempotentAndRefreshesOnce() throws Exception {
        byte[] data = catalogue();
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import/preview").contentType(MediaType.APPLICATION_JSON).content(Objects.requireNonNull(data))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(104)).andExpect(jsonPath("$.inactive").value(24))
                .andExpect(jsonPath("$.aliases").value(85)).andExpect(jsonPath("$.valid").value(true));
        assertThat(genres.count()).isZero(); verify(lexicon, never()).refresh();
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import").contentType(MediaType.APPLICATION_JSON).content(Objects.requireNonNull(data))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true)).andExpect(jsonPath("$.created").value(104));
        assertThat(genres.count()).isEqualTo(104); assertThat(aliases.count()).isEqualTo(85);
        verify(lexicon, times(1)).refresh();
        var ids = genres.findAll().stream().map(genre -> genre.getId()).sorted().toList();
        var again = run(data, true);
        assertThat(again.created()).isZero(); assertThat(again.unchanged()).isEqualTo(104); assertThat(again.aliasesAdded()).isZero();
        assertThat(genres.findAll().stream().map(genre -> genre.getId()).sorted().toList()).isEqualTo(ids);
        verify(lexicon, times(1)).refresh();
        List<TesseractOcrService.OcrWord> words = new java.util.ArrayList<>();
        for (String text : List.of("Dark Electro", "EBM", "Pop-Punk", "Power Pop", "Shoegaze"))
            words.add(new TesseractOcrService.OcrWord(text,95,0,0,10,10,"fixture",11,1,1,1));
        assertThat(lexicon.findGenres(words)).contains("Dark Electro","EBM","Pop-Punk","Power Pop","Shoegaze");
        assertThat(genres.findAllByActiveTrueOrderByCanonicalNameAsc()).hasSize(80);
        assertThat(discovery.terms()).contains("EBM", "Shoegaze");
        assertThat(discovery.terms()).doesNotContain("Aggrotech");
        assertThat(lexicon.findGenres(List.<TesseractOcrService.OcrWord>of(new TesseractOcrService.OcrWord("Aggrotech",95,0,0,10,10,"fixture",11,1,1,1)))).doesNotContain("Aggrotech");
    }
    @Test void baseRestoreIsExplicitAndDoesNotModifyFlywayHistory() {
        Long history = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class);
        var body = json.writeValueAsBytes(Map.of("schemaVersion",1,"preset","base-v1"));
        assertThat(run(body,false).created()).isEqualTo(38); assertThat(genres.count()).isZero();
        assertThat(run(body,true).created()).isEqualTo(38); assertThat(run(body,true).unchanged()).isEqualTo(38);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class)).isEqualTo(history);
    }
    @Test void preservesEditedNamesFlagsIdsAndExtraManualAliases() {
        run(payload("EBM","EBM",true,"body music"),true);
        String id = genres.findAll().getFirst().getId();
        var enriched = run(payload("EBM","EBM",true,"electronic body music"),true);
        assertThat(enriched.supplemented()).isEqualTo(1); assertThat(aliases.count()).isEqualTo(2);
        assertThat(run(payload("EBM","Electronic Body Music",false),true).valid()).isFalse();
        assertThat(genres.findById(id).orElseThrow().getDisplayName()).isEqualTo("EBM");
        assertThat(genres.findById(id).orElseThrow().isActive()).isTrue();
    }
    @Test void normalizedAliasCanonicalCollisionsAbortWholeImportWith409() throws Exception {
        run(payload("Rhythm & Blues","Rhythm & Blues",true,"rnb"),true);
        byte[] collision = payload("Other","Other",true,"RHYTHM and BLUES");
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import").contentType(MediaType.APPLICATION_JSON).content(Objects.requireNonNull(collision))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.conflicts[0].field").value("aliases"));
        assertThat(genres.count()).isEqualTo(1); assertThat(aliases.count()).isEqualTo(1);
        assertThat(run(payload("Rock’n’Roll","Rock’n’Roll",true),true).valid()).isTrue();
        assertThat(run(payload("Another","Another",true,"ROCK'N'ROLL"),true).valid()).isFalse();
        assertThat(run(payload("Post-Punk","Post-Punk",true),true).valid()).isTrue();
        assertThat(run(payload("Another","Another",true,"POST‑PUNK"),true).valid()).isFalse();
    }
    @Test void duplicateCanonicalAndAliasOwnersAreRejectedBeforeAnyWrite() {
        var rows = GenreImportCatalog.parse(payload("Test","Test",true,"test-alias","TEST ALIAS"));
        assertThat(importer.evaluate(rows,true).valid()).isFalse(); assertThat(genres.count()).isZero();
        var first = Objects.requireNonNull(GenreImportCatalog.parse(payload("One","One",true,"shared")).getFirst());
        var second = Objects.requireNonNull(GenreImportCatalog.parse(payload("Two","Two",true,"SHARED")).getFirst());
        assertThat(importer.evaluate(List.of(first,second),true).valid()).isFalse();
        assertThat(importer.evaluate(List.of(first,first),true).valid()).isFalse(); assertThat(genres.count()).isZero();
    }
    @Test void lateDatabaseFailureRollsBackAllRowsAndDoesNotRefreshCache() {
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_fixture_alias() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_import_alias BEFORE INSERT ON genre_aliases FOR EACH ROW EXECUTE FUNCTION reject_fixture_alias()");
        assertThatThrownBy(() -> run(payload("EBM","EBM",true,"electronic body music"),true)).isInstanceOf(RuntimeException.class);
        assertThat(genres.count()).isZero(); assertThat(aliases.count()).isZero(); verify(lexicon, never()).refresh();
    }
    @Test void concurrentIdenticalImportsProduceOneCatalogue() throws Exception {
        byte[] body = catalogue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> run(body,true)); var second = executor.submit(() -> run(body,true));
            assertThat(first.get(10,TimeUnit.SECONDS).created() + second.get(10,TimeUnit.SECONDS).created()).isEqualTo(104);
        }
        assertThat(genres.count()).isEqualTo(104); assertThat(aliases.count()).isEqualTo(85); verify(lexicon,times(1)).refresh();
    }
    @Test void malformedAndOversizedRequestsCannotImportAndProxyProtectionStillApplies() throws Exception {
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import").contentType(MediaType.APPLICATION_JSON).content("{\"schemaVersion\":1,\"genres\":[{}]}")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("canonicalName"))));
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import").contentType(MediaType.APPLICATION_JSON).content(new byte[GenreImportCatalog.MAX_BYTES+1])))
            .andExpect(status().isContentTooLarge());
        mvc.perform(Objects.requireNonNull(post("/api/v1/genres/import").header("X-Noizy-Api-Key","invalid").contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isForbidden());
        assertThat(genres.count()).isZero();
    }
}
