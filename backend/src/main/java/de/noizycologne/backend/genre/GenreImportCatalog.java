package de.noizycologne.backend.genre;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, bounded JSON/CSV input. Metadata is displayed as text and never persisted or fetched. */
public final class GenreImportCatalog {
    public static final int MAX_BYTES = 1_048_576;
    private static final Set<String> FIELDS = Set.of("canonicalName", "displayName", "active", "aliases", "gruppe", "status", "quelle", "hinweis");
    private static final List<String> CSV_FIELDS = List.of("canonicalName", "displayName", "active", "aliases", "gruppe", "status", "quelle", "hinweis");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private GenreImportCatalog() { }

    public record Row(int line, String canonicalName, String displayName, boolean active,
                      List<@NonNull String> aliases, Map<String, String> metadata) { }

    public static List<@NonNull Row> base() {
        try (var input = GenreImportCatalog.class.getResourceAsStream("/genres/base-v1.json")) {
            if (input == null) throw new IllegalStateException("Basisgenre-Katalog fehlt");
            return parse(input.readNBytes(MAX_BYTES + 1), false);
        } catch (IOException exception) { throw new IllegalStateException("Basisgenre-Katalog nicht lesbar", exception); }
    }

    public static List<@NonNull Row> parse(byte[] bytes) { return parse(bytes, true); }

    private static List<@NonNull Row> parse(byte[] bytes, boolean allowPreset) {
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Import höchstens 1 MiB");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw invalid(1, "Datei", "Ungültiges UTF-8"); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonNode root;
        try { root = Objects.requireNonNull(JSON.readTree(text)); }
        catch (JacksonException exception) {
            var location = exception.getLocation();
            throw invalid(location == null ? 1 : Math.max(1, location.getLineNr()), "JSON",
                    "Ungültiges JSON (Syntax, doppelte Felder oder weitere Daten)");
        } catch (NullPointerException exception) { throw invalid(1, "JSON", "JSON-Objekt erforderlich"); }
        if (!root.isObject()) throw invalid(1, "JSON", "Objekt erwartet");
        fields(root, Set.of("schemaVersion", "genres", "csv", "preset"), 1);
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").toString().equals("1"))
            throw invalid(1, "schemaVersion", "Version 1 erforderlich");
        int sources = (root.has("genres") ? 1 : 0) + (root.has("csv") ? 1 : 0) + (root.has("preset") ? 1 : 0);
        if (sources != 1) throw invalid(1, "genres", "Genau genres, csv oder preset angeben");
        if (root.has("preset")) {
            if (!allowPreset || !root.path("preset").isString() || !root.path("preset").asString().equals("base-v1"))
                throw invalid(1, "preset", "Unbekannte Basisvorgabe");
            return base();
        }
        if (root.has("csv")) {
            if (!root.path("csv").isString()) throw invalid(1, "csv", "CSV-Text erforderlich");
            return csv(root.path("csv").asString());
        }
        return parseRows(root.path("genres"), List.of());
    }

    private static List<@NonNull Row> parseRows(JsonNode rows, List<Integer> positions) {
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 250) throw invalid(1, "genres", "1 bis 250 Genres erforderlich");
        List<@NonNull Row> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            int line = positions.isEmpty() ? index + 1 : positions.get(index);
            JsonNode row = rows.path(index);
            if (!row.isObject()) throw invalid(line, "genres", "Genre-Objekt erforderlich");
            fields(row, FIELDS, line);
            String canonical = name(row.path("canonicalName"), line, "canonicalName");
            String display = name(row.path("displayName"), line, "displayName");
            if (!row.path("active").isBoolean()) throw invalid(line, "active", "true oder false erforderlich");
            JsonNode aliases = row.path("aliases");
            if (!aliases.isArray() || aliases.size() > 20) throw invalid(line, "aliases", "Liste mit höchstens 20 Aliasen erforderlich");
            List<@NonNull String> values = new ArrayList<>();
            for (JsonNode alias : aliases) values.add(name(alias, line, "aliases"));
            Map<String, String> metadata = new LinkedHashMap<>();
            for (String key : List.of("gruppe", "status", "quelle", "hinweis")) {
                if (!row.has(key)) continue;
                if (!row.path(key).isString() || row.path(key).asString().length() > 2000)
                    throw invalid(line, key, "Text mit höchstens 2000 Zeichen erforderlich");
                metadata.put(key, row.path(key).asString());
            }
            result.add(new Row(line, canonical, display, row.path("active").asBoolean(), List.copyOf(values), Map.copyOf(metadata)));
        }
        return List.copyOf(result);
    }

    private static @NonNull String name(JsonNode value, int line, String field) {
        if (!value.isString()) throw invalid(line, field, "Text erforderlich");
        String text = value.asString().strip();
        if (text.isEmpty() || text.length() > 100 || text.codePoints().anyMatch(code -> Character.isISOControl(code) || code >= 0xd800 && code <= 0xdfff)
                || GenreNormalizer.normalize(text).isEmpty()) throw invalid(line, field, "1 bis 100 gültige Zeichen erforderlich");
        return text;
    }

    private static void fields(JsonNode node, Set<String> allowed, int line) {
        for (String field : node.propertyNames()) if (!allowed.contains(field)) throw invalid(line, field, "Unbekanntes Feld");
    }

    private record CsvRow(int line, List<String> cells) { }

    private static List<@NonNull Row> csv(String source) {
        // Semicolon CSV with RFC-style quoting, escaped quotes and physical line positions.
        String text = source.startsWith("\uFEFF") ? source.substring(1) : source;
        List<CsvRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        int line = 1, rowLine = 1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i == text.length() ? '\n' : text.charAt(i);
            if (quoted) {
                if (i == text.length()) throw invalid(rowLine, "CSV", "Nicht geschlossenes Anführungszeichen");
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { cell.append(c); if (c == '\n') line++; }
                continue;
            }
            if (c == '"' && cell.isEmpty() && !closed) { quoted = true; continue; }
            if (c != ';' && c != '\n' && c != '\r') {
                if (closed || c == '"') throw invalid(line, "CSV", "Ungültige Anführungszeichen");
                cell.append(c); continue;
            }
            cells.add(cell.toString()); cell.setLength(0); closed = false;
            if (cells.size() > CSV_FIELDS.size()) throw invalid(rowLine, "CSV", "Zu viele Spalten");
            if (c == ';') continue;
            if (!(cells.size() == 1 && cells.getFirst().isEmpty())) rows.add(new CsvRow(rowLine, List.copyOf(cells)));
            cells.clear();
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            rowLine = ++line;
            if (rows.size() > 251) throw invalid(rowLine, "CSV", "Höchstens 250 Genres");
        }
        if (rows.isEmpty() || !rows.getFirst().cells().equals(CSV_FIELDS)) throw invalid(1, "CSV", "Erwartete Kopfzeile: " + String.join(";", CSV_FIELDS));
        var array = JSON.createArrayNode();
        for (int i = 1; i < rows.size(); i++) {
            CsvRow row = rows.get(i);
            if (row.cells().size() != CSV_FIELDS.size()) throw invalid(row.line(), "CSV", "Genau acht Spalten erforderlich");
            var object = array.addObject();
            for (int col = 0; col < CSV_FIELDS.size(); col++) {
                String key = CSV_FIELDS.get(col), value = row.cells().get(col);
                if (key.equals("active")) {
                    if (!value.equals("true") && !value.equals("false")) throw invalid(row.line(), key, "true oder false erforderlich");
                    object.put(key, Boolean.parseBoolean(value));
                } else if (key.equals("aliases")) {
                    var aliases = object.putArray(key);
                    if (!value.isBlank()) for (String alias : value.split(" \\| ", -1)) aliases.add(alias);
                } else object.put(key, value);
            }
        }
        return parseRows(array, rows.subList(1, rows.size()).stream().map(row -> row.line()).toList());
    }

    private static ResponseStatusException invalid(int line, String field, String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zeile/Eintrag " + line + ", Feld " + field + ": " + message);
    }
}
