package de.noizycologne.backend.maintenance;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MaintenanceGateTests {
    @Test void failedCheckoutIsCooledInsteadOfRepeatingBlockingAcquisitionPerRequest() throws Exception {
        var source=mock(DataSource.class); when(source.getConnection()).thenThrow(new SQLException("fixture"));
        var gate=new MaintenanceGate(source);
        assertThatThrownBy(gate::enter).hasMessageContaining("nicht verfügbar");
        assertThatThrownBy(gate::enter).hasMessageContaining("vorübergehend");
        verify(source,times(1)).getConnection();
    }
    @Test void lostAcquireResponseAbortsPhysicalConnectionAndPermanentlyClosesGate() throws Exception {
        var source=mock(DataSource.class); var connection=mock(Connection.class); var statement=mock(Statement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("lost response"));
        var gate=new MaintenanceGate(source);
        assertThatThrownBy(gate::exclusive).hasMessageContaining("nicht verfügbar");
        verify(connection).abort(any(java.util.concurrent.Executor.class)); verify(connection).close();
        assertThatThrownBy(gate::enter).hasMessageContaining("gestört");
    }
    @Test void unsuccessfulAbortNeverReturnsPossiblyLockedConnectionToPool() throws Exception {
        var source=mock(DataSource.class); var connection=mock(Connection.class); var statement=mock(Statement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("lost response"));
        java.util.Objects.requireNonNull(doThrow(new SQLException("abort failed")).when(connection)).abort(any(java.util.concurrent.Executor.class));
        var gate=new MaintenanceGate(source);
        assertThatThrownBy(gate::exclusive).hasMessageContaining("nicht verfügbar"); verify(connection,never()).close();
        assertThatThrownBy(gate::exclusive).hasMessageContaining("gestört");
    }
    @Test void lostUnlockResponsePoisonsAndAbortsInsteadOfReturningHeldSessionLock() throws Exception {
        var source=mock(DataSource.class); var connection=mock(Connection.class); var statement=mock(Statement.class); var result=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_try_advisory_lock(73624604)")).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(true);
        when(statement.executeQuery("SELECT pg_advisory_unlock(73624604)")).thenThrow(new SQLException("lost unlock"));
        var gate=new MaintenanceGate(source); var lease=gate.exclusive();
        assertThatThrownBy(lease::close).hasMessageContaining("freigegeben");
        verify(connection).abort(any(java.util.concurrent.Executor.class));
        assertThatThrownBy(gate::enter).hasMessageContaining("gestört");
    }
    @Test void concurrentRequestsShareOneBoundedConnectionAndReleaseOnlyAfterTheLastLease() throws Exception {
        var source=mock(DataSource.class); var connection=mock(Connection.class); var statement=mock(Statement.class);
        var acquired=mock(ResultSet.class); var pending=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_try_advisory_lock_shared(73624604)")).thenReturn(acquired);
        when(statement.executeQuery("SELECT pg_advisory_unlock_shared(73624604)")).thenReturn(acquired);
        when(statement.executeQuery("SELECT pending_reset IS NOT NULL FROM maintenance_state WHERE singleton")).thenReturn(pending);
        when(acquired.next()).thenReturn(true); when(acquired.getBoolean(1)).thenReturn(true); when(pending.next()).thenReturn(true);
        var gate=new MaintenanceGate(source); var leases=new java.util.ArrayList<MaintenanceGate.Lease>();
        for(int index=0;index<64;index++) leases.add(gate.enter());
        assertThatThrownBy(gate::enter).hasMessageContaining("Zu viele");
        assertThatThrownBy(gate::exclusive).hasMessageContaining("aktiv");
        verify(source,times(1)).getConnection();
        for(int index=0;index<63;index++) leases.get(index).close();
        verify(connection,never()).close();
        leases.getLast().close(); leases.getLast().close(); verify(connection,times(1)).close();
    }
}
