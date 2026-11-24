package de.noizycologne.backend.editorial;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Single shared caption pretext with a monotonically increasing revision for optimistic updates. */
@Service
public class CaptionPretextConfigService {
    public static final String DEFAULT_PRETEXT = "noizycologne";
    static final int MAX_LENGTH = 100;

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
            Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})"),
            Pattern.compile("(AKIA|ASIA)[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)(api[_-]?key|password|passwd|secret|token)\\s*[:=]"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{12,}"),
            Pattern.compile("(?i)https?://[^\\s/@]+:[^\\s/@]+@"),
            Pattern.compile("[A-Za-z0-9+/_-]{40,}={0,2}")
    );

    private final JdbcTemplate jdbc;

    public CaptionPretextConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CaptionPretextResponse get() {
        return current(false);
    }

    @Transactional
    public CaptionPretextResponse update(CaptionPretextRequest request) {
        String pretext = Objects.requireNonNull(request.pretext());
        validate(pretext);
        CaptionPretextResponse current = current(true);
        Long expected = request.expectedRevision();
        if (expected != null && expected.longValue() != current.revision()) {
            throw conflict("Der Pre-Text wurde inzwischen geändert. Bitte neu laden und erneut speichern.");
        }
        if (current.pretext().equals(pretext)) return current; // Idempotent: an unchanged value keeps its revision.
        int updated = jdbc.update("UPDATE editorial_caption_config SET pretext=?, revision=revision+1 WHERE singleton AND revision=?",
                pretext, Long.valueOf(current.revision()));
        if (updated != 1) throw conflict("Der Pre-Text wurde inzwischen geändert. Bitte neu laden und erneut speichern.");
        return current(false);
    }

    private CaptionPretextResponse current(boolean lock) {
        @Nullable CaptionPretextResponse value = jdbc.queryForObject(
                "SELECT pretext, revision FROM editorial_caption_config WHERE singleton" + (lock ? " FOR UPDATE" : ""),
                (result, row) -> new CaptionPretextResponse(
                        Objects.requireNonNull(result.getString("pretext")), result.getLong("revision")));
        if (value == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Die Caption-Konfiguration ist nicht verfügbar.");
        return value;
    }

    private static void validate(String pretext) {
        if (pretext.isEmpty() || pretext.length() > MAX_LENGTH)
            throw badRequest("Der Pre-Text muss zwischen 1 und " + MAX_LENGTH + " Zeichen lang sein.");
        if (pretext.chars().anyMatch(Character::isISOControl))
            throw badRequest("Der Pre-Text darf keine Steuerzeichen enthalten.");
        for (Pattern pattern : SECRET_PATTERNS) {
            if (pattern.matcher(pretext).find())
                throw badRequest("Der Pre-Text darf keine Zugangsdaten, Tokens oder URLs mit Anmeldedaten enthalten.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
