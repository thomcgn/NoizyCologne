package de.noizycologne.backend.discovery;

import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Objects;

/** One session lock for the entire scan/reset, including network requests, across JVMs. */
@Component
public class DiscoveryWorkLock {
    private final DiscoveryStore store;
    public DiscoveryWorkLock(DiscoveryStore store) { this.store=store; }
    public Lease tryAcquire() throws SQLException {
        Connection connection=Objects.requireNonNull(store.jdbc().getDataSource()).getConnection();
        try (var stmt=connection.createStatement();var result=stmt.executeQuery("SELECT pg_try_advisory_lock(73624601)")) {
            result.next();
            if (result.getBoolean(1)) return new Lease(connection);
        } catch (SQLException e) { connection.close();throw e; }
        connection.close();return null;
    }
    public record Lease(Connection connection) implements AutoCloseable {
        @Override public void close() throws SQLException {
            try (var stmt=connection.createStatement()) { stmt.execute("SELECT pg_advisory_unlock(73624601)"); }
            finally { connection.close(); }
        }
    }
}
