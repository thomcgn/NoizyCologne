package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.discovery.DiscoveryWorkLock;
import de.noizycologne.backend.media.GenreLexicon;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FactoryResetServiceTests {
    @Test void activeDiscoveryIsRejectedBeforeAnyExclusiveMutationLockCanDisturbItsWork() throws Exception {
        var discovery=mock(DiscoveryWorkLock.class); var gate=mock(MaintenanceGate.class);
        when(discovery.tryAcquire()).thenReturn(null);
        var service=new FactoryResetService(mock(JdbcTemplate.class),discovery,gate,mock(ResetJournal.class),mock(GenreLexicon.class));
        assertThatThrownBy(service::preview).hasMessageContaining("Discovery-Lauf ist aktiv");
        assertThatThrownBy(() -> service.execute("a".repeat(64))).hasMessageContaining("Discovery-Lauf ist aktiv");
        verify(gate,never()).exclusive();
    }

    @Test void busyMutationGateReleasesTheAlreadyAcquiredDiscoveryLeaseWithoutTouchingData() throws Exception {
        var discovery=mock(DiscoveryWorkLock.class); var gate=mock(MaintenanceGate.class); var lease=mock(DiscoveryWorkLock.Lease.class);
        when(discovery.tryAcquire()).thenReturn(lease);
        when(gate.exclusive()).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"fixture busy"));
        var jdbc=mock(JdbcTemplate.class);
        var service=new FactoryResetService(jdbc,discovery,gate,mock(ResetJournal.class),mock(GenreLexicon.class));
        assertThatThrownBy(service::preview).hasMessageContaining("fixture busy");
        verify(lease).close(); verifyNoInteractions(jdbc);
    }
}
