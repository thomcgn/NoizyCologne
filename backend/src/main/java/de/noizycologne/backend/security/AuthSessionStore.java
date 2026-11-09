package de.noizycologne.backend.security;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded, shared PostgreSQL state. State changes are serialized across API instances; validation reads committed state. */
@Service
public final class AuthSessionStore {
    static final int SESSION_CAPACITY = 2048;
    static final int BUCKET_CAPACITY = 4096;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthSessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    AuthSessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.clock = clock;
    }

    public record Attempt(boolean allowed, long retryAfter) { }

    public void register(String nonceHash, String generation, long expiresAt) {
        hash(nonceHash); hash(generation);
        Instant now = clock.instant();
        if (expiresAt <= now.getEpochSecond() || expiresAt > now.getEpochSecond() + 8 * 3600 + 30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige Sitzungsdauer.");
        locked(() -> {
            cleanup(now);
            if (count("auth_sessions") >= SESSION_CAPACITY)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Sitzungskapazität erreicht.");
            int inserted = jdbc.update("INSERT INTO auth_sessions(nonce_hash,generation,expires_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                nonceHash, generation, Timestamp.from(Instant.ofEpochSecond(expiresAt)));
            if (inserted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Sitzung bereits registriert.");
            return true;
        });
    }

    public boolean validate(String nonceHash, String generation) {
        hash(nonceHash); hash(generation);
        // Reads must not turn parallel Caddy verification into spurious logouts.
        // PostgreSQL observes committed revocations; the write lock only serializes state changes.
        Instant now = clock.instant();
        Long count = jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE nonce_hash=? AND generation=? AND NOT revoked AND expires_at>?",
            Long.class, nonceHash, generation, Timestamp.from(now));
        boolean valid = Long.valueOf(1).equals(count);
        if (!valid) jdbc.update("DELETE FROM auth_sessions WHERE nonce_hash=? AND expires_at<=?", nonceHash, Timestamp.from(now));
        return valid;
    }

    public void revoke(String nonceHash) {
        hash(nonceHash);
        locked(() -> {
            cleanup(clock.instant());
            jdbc.update("UPDATE auth_sessions SET revoked=TRUE WHERE nonce_hash=?", nonceHash);
            return true;
        });
    }

    public @NonNull Attempt attempt(String accountHash, String addressHash) {
        hash(accountHash); hash(addressHash);
        if (accountHash.equals(addressHash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Getrennte Anmeldekennungen erforderlich.");
        return locked(() -> {
            Instant now = clock.instant();
            cleanup(now);
            long retry = Math.max(retryAfter(accountHash, 25, now), retryAfter(addressHash, 5, now));
            if (retry > 0) return new Attempt(false, retry);
            Long existing = jdbc.queryForObject("SELECT count(*) FROM auth_login_buckets WHERE bucket_hash IN (?,?)",
                Long.class, accountHash, addressHash);
            if (count("auth_login_buckets") + 2 - Objects.requireNonNull(existing) > BUCKET_CAPACITY)
                return new Attempt(false, 900);
            increment(accountHash, now);
            increment(addressHash, now);
            return new Attempt(true, 0);
        });
    }

    public void clear(String accountHash, String addressHash) {
        hash(accountHash); hash(addressHash);
        locked(() -> {
            cleanup(clock.instant());
            jdbc.update("DELETE FROM auth_login_buckets WHERE bucket_hash IN (?,?)", accountHash, addressHash);
            return true;
        });
    }

    private long retryAfter(String key, int limit, Instant now) {
        var rows = jdbc.queryForList("SELECT attempts,expires_at FROM auth_login_buckets WHERE bucket_hash=?", key);
        if (rows.isEmpty()) return 0;
        var row = rows.getFirst();
        if (!(row.get("attempts") instanceof Number attempts) || !(row.get("expires_at") instanceof Timestamp expiry))
            throw new IllegalStateException("Ungültiger Anmeldespeicher.");
        return attempts.intValue() >= limit ? Math.max(1, expiry.toInstant().getEpochSecond() - now.getEpochSecond()) : 0;
    }

    private void increment(String key, Instant now) {
        jdbc.update("INSERT INTO auth_login_buckets(bucket_hash,attempts,expires_at) VALUES (?,1,?) "
            + "ON CONFLICT(bucket_hash) DO UPDATE SET attempts=LEAST(auth_login_buckets.attempts+1,26)",
            key, Timestamp.from(now.plusSeconds(900)));
    }

    private void cleanup(Instant now) {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at<=?", Timestamp.from(now));
        jdbc.update("DELETE FROM auth_login_buckets WHERE expires_at<=?", Timestamp.from(now));
    }

    private long count(String table) {
        // Table names only come from the two constants above, never an HTTP request.
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }

    private <T> @NonNull T locked(Supplier<T> operation) {
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '1500ms'");
            jdbc.execute("SET LOCAL lock_timeout = '500ms'");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(73624603)", Boolean.class)))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Anmeldespeicher ausgelastet. Bitte erneut versuchen.");
            return Objects.requireNonNull(operation.get());
        }));
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige Anmeldekennung.");
    }
}
