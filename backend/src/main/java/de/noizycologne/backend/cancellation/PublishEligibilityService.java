package de.noizycologne.backend.cancellation;

import de.noizycologne.backend.discovery.DiscoveryStore;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** All future publishers must call this server-side guard immediately before sending. */
@Service
public class PublishEligibilityService {
    private final DiscoveryStore store;
    public PublishEligibilityService(DiscoveryStore store) { this.store=store; }
    public List<String> blockers(String entry) {
        var result=new ArrayList<>(store.jdbc().queryForList("SELECT kind || ': ' || reason FROM event_changes WHERE calendar_entry_id=? AND blocks_publication ORDER BY created_at",String.class,entry));
        if (Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM event_cancellations WHERE calendar_entry_id=?)",Boolean.class,entry))) result.add("CANCELLED: Veranstaltung bestätigt abgesagt");
        if(Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM calendar_entries WHERE id=? AND event_status='PLANNED')",Boolean.class,entry))) result.add("PLANNED: Geplant – nicht bestätigt; Veröffentlichung gesperrt");
        return result;
    }
    public void requireOriginalApproval(String entry) {
        var reasons=blockers(entry);
        if (!reasons.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,reasons.stream().anyMatch(reason -> reason.startsWith("PLANNED:"))
            ? "Geplant – nicht bestätigt; Veröffentlichung bleibt gesperrt"
            : "Veranstaltungsänderung oder Absage zuerst klären; Originalpost bleibt gesperrt");
    }
}
