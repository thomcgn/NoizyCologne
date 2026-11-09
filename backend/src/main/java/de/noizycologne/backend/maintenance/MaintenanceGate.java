package de.noizycologne.backend.maintenance;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** One shared coordination connection per JVM, covering complete HTTP/file operations. */
@Component
public final class MaintenanceGate {
    private static final int MAX_ACTIVE_REQUESTS = 64;
    private final DataSource dataSource;
    private @Nullable Connection coordinator;
    private @Nullable Connection quarantined;
    private int active;
    private boolean exclusive;
    private boolean poisoned;
    private boolean cooling;
    private long failedAt;

    public MaintenanceGate(DataSource dataSource) { this.dataSource = dataSource; }

    synchronized void stop() { poisoned = true; }

    public synchronized Lease enter() {
        requireHealthy();
        if (exclusive) throw unavailable("Wartungsarbeiten sind aktiv. Bitte später erneut versuchen.", null);
        if (active >= MAX_ACTIVE_REQUESTS)
            throw unavailable("Zu viele gleichzeitige Anfragen. Bitte erneut versuchen.", null);
        if (active == 0) {
            Connection connection = acquire(false);
            try {
                if (pending(connection)) throw unavailable("Ein bestätigter Reset wartet auf den sicheren Abschluss der Dateibereinigung. Bitte den Administrator informieren.", null);
                coordinator = connection;
            } catch (SQLException | RuntimeException exception) {
                release(connection, false);
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw unavailable("Wartungszustand ist nicht verfügbar.", exception);
            }
        }
        active++;
        return new Lease(this, null);
    }

    public synchronized Lease exclusive() {
        requireHealthy();
        if (exclusive || active > 0) throw busy();
        Connection connection = acquire(true);
        exclusive = true;
        return new Lease(this, connection);
    }

    private Connection acquire(boolean write) {
        Connection connection;
        try { connection = Objects.requireNonNull(dataSource.getConnection()); }
        catch (SQLException exception) {
            cooling = true; failedAt = System.nanoTime();
            throw unavailable("Wartungssperre ist nicht verfügbar.", exception);
        }
        boolean acquired;
        try (var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.setQueryTimeout(2);
            try (var result = statement.executeQuery(write
                    ? "SELECT pg_try_advisory_lock(73624604)"
                    : "SELECT pg_try_advisory_lock_shared(73624604)")) {
                if (!result.next()) throw new SQLException("Missing maintenance lock response");
                acquired = result.getBoolean(1);
            }
        } catch (SQLException exception) {
            // A failed query may have acquired the session lock before its response was lost.
            discard(connection, exception);
            throw unavailable("Wartungssperre ist nicht verfügbar.", exception);
        }
        if (acquired) return connection;
        try { connection.close(); }
        catch (SQLException exception) {
            discard(connection, exception);
            throw unavailable("Wartungsverbindung konnte nicht geschlossen werden.", exception);
        }
        if (write) throw busy();
        throw unavailable("Wartungsarbeiten sind aktiv. Bitte später erneut versuchen.", null);
    }

    static boolean pending(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var result = statement.executeQuery("SELECT pending_reset IS NOT NULL FROM maintenance_state WHERE singleton")) {
                if (!result.next()) throw new SQLException("Maintenance state is missing");
                return result.getBoolean(1);
            }
        }
    }

    private synchronized void leave(@Nullable Connection owned) {
        if (owned != null) {
            try { release(owned, true); }
            finally { exclusive = false; }
        } else {
            if (active <= 0) throw new IllegalStateException("Unbalanced maintenance lease");
            active--;
            if (active == 0) {
                Connection connection = Objects.requireNonNull(coordinator);
                coordinator = null;
                release(connection, false);
            }
        }
    }

    private void release(Connection connection, boolean write) {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (var result = statement.executeQuery(write
                    ? "SELECT pg_advisory_unlock(73624604)"
                    : "SELECT pg_advisory_unlock_shared(73624604)")) {
                if (!result.next() || !result.getBoolean(1)) throw new SQLException("Maintenance lock was lost");
            }
        } catch (SQLException exception) {
            discard(connection, exception);
            throw unavailable("Wartungssperre konnte nicht sicher freigegeben werden.", exception);
        }
        try { connection.close(); }
        catch (SQLException exception) {
            discard(connection, exception);
            throw unavailable("Wartungsverbindung konnte nicht sicher geschlossen werden.", exception);
        }
    }

    private void discard(Connection connection, SQLException original) {
        poisoned = true;
        try {
            connection.abort(command -> Objects.requireNonNull(command).run());
            connection.close();
        } catch (SQLException | RuntimeException exception) {
            original.addSuppressed(exception);
            // Never return a possibly locked physical connection to the pool.
            // One quarantined connection is bounded; the gate stays closed until restart.
            quarantined = connection;
        }
    }

    private void requireHealthy() {
        if (cooling && System.nanoTime() - failedAt < 1_000_000_000L)
            throw unavailable("Wartungsspeicher vorübergehend nicht erreichbar. Bitte erneut versuchen.", null);
        cooling = false;
        if (poisoned || quarantined != null)
            throw unavailable("Wartungskoordination gestört. Bitte den Administrator informieren.", null);
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Eine Anfrage oder Wartung ist aktiv. Es wurde kein Reset gestartet.");
    }

    private static ResponseStatusException unavailable(String message, @Nullable Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    public static final class Lease implements AutoCloseable {
        private final MaintenanceGate gate;
        private final @Nullable Connection connection;
        private volatile boolean closed;
        private Lease(MaintenanceGate gate, @Nullable Connection connection) {
            this.gate = gate; this.connection = connection;
        }
        public Connection connection() {
            if (connection == null || closed) throw new IllegalStateException("Exclusive lease required");
            return connection;
        }
        @Override public synchronized void close() {
            if (!closed) { closed = true; gate.leave(connection); }
        }
    }
}
