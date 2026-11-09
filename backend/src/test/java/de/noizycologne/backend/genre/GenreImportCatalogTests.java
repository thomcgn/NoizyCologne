package de.noizycologne.backend.genre;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenreImportCatalogTests {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void suppliedCsvHasExpectedCountsAndMetadata() throws Exception {
        try (var input = getClass().getResourceAsStream("/genres/catalog-2026-11-09.csv")) {
            String csv = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\n", "\r\n");
            String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("schemaVersion", 1, "csv", csv));
            var rows = GenreImportCatalog.parse(bytes(json));
            assertThat(rows).hasSize(104);
            assertThat(rows.stream().filter(GenreImportCatalog.Row::active)).hasSize(80);
            assertThat(rows.stream().mapToInt(row -> row.aliases().size()).sum()).isEqualTo(85);
            assertThat(Objects.requireNonNull(rows.getFirst()).line()).isEqualTo(2);
            assertThat(Objects.requireNonNull(rows.getFirst()).metadata()).containsKey("quelle");
        }
    }
    @Test void baseCatalogueContainsOriginalThirtyEightAndNoNewAutomaticPopulation() {
        var rows = GenreImportCatalog.base();
        assertThat(rows).hasSize(38).allMatch(GenreImportCatalog.Row::active);
        assertThat(rows.stream().map(GenreImportCatalog.Row::canonicalName)).contains("Rock’n’Roll", "Electronicore", "Sonstiges");
    }
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{}", "{\"schemaVersion\":1,\"genres\":[]}",
        "{\"schemaVersion\":1,\"schemaVersion\":1,\"preset\":\"base-v1\"}",
        "{\"schemaVersion\":1,\"preset\":\"base-v1\"} {}", "{\"schemaVersion\":1,\"preset\":\"unknown\"}",
        "{\"schemaVersion\":1,\"preset\":\"base-v1\",\"genres\":[]}",
        "{\"schemaVersion\":1,\"genres\":[{\"canonicalName\":\"EBM\"}]}",
        "{\"schemaVersion\":1,\"genres\":[{\"canonicalName\":\"EBM\",\"displayName\":\"EBM\",\"active\":true,\"aliases\":[],\"unknown\":1}]}"})
    void rejectsMalformedOrUnknownData(String json) {
        assertThatThrownBy(() -> GenreImportCatalog.parse(bytes(json))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsByteLimitsAndBrokenUtf8BeforeParsing() {
        assertThatThrownBy(() -> GenreImportCatalog.parse(new byte[GenreImportCatalog.MAX_BYTES + 1])).hasMessageContaining("413");
        assertThatThrownBy(() -> GenreImportCatalog.parse(new byte[]{(byte) 0xc3, (byte) 0x28})).hasMessageContaining("UTF-8");
    }
    @Test void csvQuotingPreservesMetadataAndPhysicalLineErrors() {
        String header = "canonicalName;displayName;active;aliases;gruppe;status;quelle;hinweis\r\n";
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String csv = header + "EBM;EBM;true;electronic body music;;;https://example.test;\"with ; separator\nsecond line\"\r\n";
        var rows = GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion", 1, "csv", csv)));
        assertThat(Objects.requireNonNull(rows.getFirst()).metadata().get("hinweis")).isEqualTo("with ; separator\nsecond line");
        String bad = csv + "Pop;Pop;yes;;;;;\n";
        assertThatThrownBy(() -> GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion", 1, "csv", bad))))
                .hasMessageContaining("Zeile/Eintrag 4, Feld active");
        assertThatThrownBy(() -> GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion", 1, "csv", header + "\"unclosed"))))
                .hasMessageContaining("Anführungszeichen");
    }

    @Test void jsonSyntaxReportsItsPhysicalLineAndBaseNamesMatchV6() throws Exception {
        assertThatThrownBy(() -> GenreImportCatalog.parse(bytes("{\n\"schemaVersion\":1,\n!}"))).hasMessageContaining("Zeile/Eintrag 3, Feld JSON");
        try (var input = getClass().getResourceAsStream("/db/migration/V6__add_dynamic_genres.sql")) {
            String source = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            var original = java.util.regex.Pattern.compile("\\('([^']+)', '([^']+)', '([^']+)', TRUE\\)").matcher(source).results().map(match -> match.group(2)).toList();
            assertThat(GenreImportCatalog.base().stream().map(row -> row.canonicalName()).toList()).containsExactlyElementsOf(original);
        }
    }
    @Test void fieldAndCollectionLimitsAreEnforced() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var valid = java.util.Map.of("canonicalName", "A".repeat(100), "displayName", "Display", "active", true, "aliases", java.util.List.of());
        assertThat(GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion",1,"genres", java.util.Collections.nCopies(250,valid))))).hasSize(250);
        assertThatThrownBy(() -> GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion",1,"genres", java.util.Collections.nCopies(251,valid))))).hasMessageContaining("250");
        var tooLong = java.util.Map.of("canonicalName", "A".repeat(101), "displayName", "Display", "active", true, "aliases", java.util.List.of());
        assertThatThrownBy(() -> GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion",1,"genres", java.util.List.of(tooLong))))).hasMessageContaining("canonicalName");
        var tooMany = java.util.Map.of("canonicalName", "EBM", "displayName", "EBM", "active", true, "aliases", java.util.Collections.nCopies(21,"alias"));
        assertThatThrownBy(() -> GenreImportCatalog.parse(json.writeValueAsBytes(java.util.Map.of("schemaVersion",1,"genres", java.util.List.of(tooMany))))).hasMessageContaining("aliases");
    }
    @Test void unknownContentLengthStillEnforcesActualBodyLimitBeforeServiceInvocation() throws Exception {
        var service = org.mockito.Mockito.mock(GenreImportService.class);
        var controller = new GenreImportController(service);
        var request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getContentLengthLong()).thenReturn(-1L);
        org.mockito.Mockito.when(request.getInputStream()).thenReturn(new org.springframework.mock.web.DelegatingServletInputStream(new java.io.ByteArrayInputStream(new byte[GenreImportCatalog.MAX_BYTES + 1])));
        assertThatThrownBy(() -> controller.apply(request)).hasMessageContaining("413");
        org.mockito.Mockito.verifyNoInteractions(service);
    }
    @Test void normalizesIdenticallyForCaseUnicodeApostrophesAndAmpersands() {
        assertThat(GenreNormalizer.normalize("Rock’n’Roll")).isEqualTo(GenreNormalizer.normalize("ROCK'N'ROLL"));
        assertThat(GenreNormalizer.normalize("Rhythm & Blues")).isEqualTo(GenreNormalizer.normalize("rhythm and blues"));
        assertThat(GenreNormalizer.normalize("Post‑Punk")).isEqualTo(GenreNormalizer.normalize("Post-Punk"));
        assertThat(GenreNormalizer.normalize("Yé-yé")).isEqualTo(GenreNormalizer.normalize("Ye\u0301-ye\u0301"));
    }
}
