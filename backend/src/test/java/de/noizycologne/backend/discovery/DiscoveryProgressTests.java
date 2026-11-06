package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DiscoveryProgressTests {
    private Map<String,Object> row(String kind,String status,int total,int completed) {
        return Map.of("kind",kind,"status",status,"total_units",total,"completed_units",completed,"failed_units",0);
    }
    @Test void roundsClampsAndKeepsActualFailedAndInterruptedProgress() {
        for (String status:List.of("RUNNING","FAILED","INTERRUPTED")) {
            assertThat(DiscoveryProgress.forApi(row("SCAN",status,3,1))).containsEntry("progressPercent",33);
            assertThat(DiscoveryProgress.forApi(row("MONITOR",status,3,2))).containsEntry("progressPercent",67);
        }
        assertThat(DiscoveryProgress.forApi(row("SCAN","RUNNING",2,9))).containsEntry("progressPercent",100);
        assertThat(DiscoveryProgress.forApi(row("SCAN","RUNNING",2,-1))).containsEntry("progressPercent",0);
    }
    @Test void completedPartialEmptyAndLegacyOperationsHaveHonestMeaning() {
        for (String status:List.of("COMPLETED","PARTIAL"))
            assertThat(DiscoveryProgress.forApi(row("SCAN",status,3,3))).containsEntry("progressPercent",100);
        assertThat(DiscoveryProgress.forApi(row("SCAN","COMPLETED",0,0))).containsEntry("progressPercent",100);
        assertThat(DiscoveryProgress.forApi(row("SCAN","INTERRUPTED",0,0))).containsEntry("progressPercent",0);
        assertThat(DiscoveryProgress.forApi(Map.of("kind","SCAN","status","COMPLETED"))).containsEntry("progressPercent",null);
        for (String kind:List.of("ALL","IGNORED","RESCAN"))
            assertThat(DiscoveryProgress.forApi(row(kind,"COMPLETED",3,3))).containsEntry("progressPercent",null);
    }
}
