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
        return result;
    }
    public void requireOriginalApproval(String entry) {
        if (!blockers(entry).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Veranstaltungsänderung oder Absage zuerst klären; Originalpost bleibt gesperrt");
    }
}
