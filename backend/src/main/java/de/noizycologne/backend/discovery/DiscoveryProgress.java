package de.noizycologne.backend.discovery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Committed independently of individual source processing so polling sees real work. */
@Service
public class DiscoveryProgress {
    private final DiscoveryStore store;
    public DiscoveryProgress(DiscoveryStore store) { this.store=store; }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void begin(String id,int total) {
        store.jdbc().update("UPDATE discovery_operations SET total_units=?,completed_units=0,failed_units=0,phase='PREPARING',progress_updated_at=now() WHERE id=? AND kind IN ('SCAN','MONITOR')",total,id);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void current(String id,String phase,String label) {
        store.jdbc().update("UPDATE discovery_operations SET phase=?,current_label=?,progress_updated_at=now() WHERE id=? AND total_units IS NOT NULL",phase,label,id);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(String id,boolean failed) {
        store.jdbc().update("UPDATE discovery_operations SET completed_units=completed_units+1,failed_units=failed_units+?,progress_updated_at=now() WHERE id=? AND completed_units<total_units",failed ? 1 : 0,id);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(String id,String status) {
        store.jdbc().update("UPDATE discovery_operations SET phase=?,current_label=CASE WHEN ? IN ('COMPLETED','PARTIAL') THEN NULL ELSE current_label END,progress_updated_at=now() WHERE id=? AND total_units IS NOT NULL",status,status,id);
    }
    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Discovery unterbrochen");
    }
    public static Map<String,Object> forApi(Map<String,Object> row) {
        var result=DiscoveryStore.forApi(row);
        for (String field:List.of("total_units","completed_units","failed_units","current_label","phase","progress_updated_at")) {
            String name=switch(field) {
                case "total_units" -> "totalUnits";
                case "completed_units" -> "completedUnits";
                case "failed_units" -> "failedUnits";
                case "current_label" -> "currentLabel";
                case "progress_updated_at" -> "updatedAt";
                default -> field;
            };
            result.put(name,result.get(field));
        }
        Object percent=null;
        if (("SCAN".equals(row.get("kind")) || "MONITOR".equals(row.get("kind")))
                && row.get("total_units") instanceof Number total && row.get("completed_units") instanceof Number completed) {
            percent="COMPLETED".equals(row.get("status")) || "PARTIAL".equals(row.get("status")) ? 100
                : total.intValue()==0 ? 0 : Math.clamp(Math.round(completed.doubleValue()/total.doubleValue()*100),0,100);
        }
        result.put("progressPercent",percent);
        return result;
    }
}
