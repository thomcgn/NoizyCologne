package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.venue.Venue;
import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Serializes reconciliation with editorial decisions and calendar imports. */
@Service
@Transactional
public class EventCandidateProcessor {
    private final DiscoveryStore store;
    private final EventChangeMonitor monitor;
    private final DiscoveryEvaluationService evaluator;
    private final EventImportService importer;
    public EventCandidateProcessor(DiscoveryStore store,DiscoveryEvaluationService evaluator,EventImportService importer,EventChangeMonitor monitor) {
        this.store=store;this.evaluator=evaluator;this.importer=importer;this.monitor=monitor;
    }
    public void process(Venue venue,String sourceId,EventExtractor.Event event,List<@NonNull PartySeries> known,
            Map<String,List<String>> aliases,List<String> genres,boolean approved) {
        process(venue,sourceId,event,known,aliases,genres,approved,null);
    }
    private static boolean compatibleFallback(EventExtractor.Event before,EventExtractor.Event current,String venue) {
        if (!EventNormalizer.text(before.title()).equals(EventNormalizer.text(current.title()))
                || EventChangeMonitor.date(before)==null || !Objects.equals(EventChangeMonitor.date(before),EventChangeMonitor.date(current))
                || !EventLifecycle.signal(before).equals("ACTIVE") || !EventLifecycle.signal(current).equals("ACTIVE")
                || !EventVerification.assess(current,venue).sufficient()) return false;
        if (!before.location().isBlank() && !current.location().isBlank()
                && !EventNormalizer.text(before.location()).equals(EventNormalizer.text(current.location()))) return false;
        return java.util.stream.Stream.concat(before.starts().stream(),current.starts().stream())
            .map(EventNormalizer::time).filter(Objects::nonNull).distinct().count()<=1;
    }
    public void process(Venue venue,String sourceId,EventExtractor.Event sourceEvent,List<@NonNull PartySeries> known,
            Map<String,List<String>> aliases,List<String> genres,boolean approved,String operation) {
        var event=EventPrivacy.safe(sourceEvent);
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        String url;
        try { url=SafeUrls.parse(event.url()).toString(); }
        catch (IllegalArgumentException e) { url=store.jdbc().queryForObject("SELECT url FROM discovery_sources WHERE id=?",String.class,sourceId); }
        String identity=EventNormalizer.hash(event.identity().isBlank() ? EventNormalizer.text(event.title())+"|"+event.starts() : event.identity());
        String fingerprint=EventNormalizer.hash(event.title()+event.starts()+event.state()+event.evidence());
        List<Map<String,Object>> previous=store.jdbc().queryForList("""
            SELECT c.* FROM discovery_candidates c WHERE c.venue_id=? AND
            (c.identity_key=? OR c.id IN (SELECT candidate_id FROM discovery_candidate_identities WHERE venue_id=? AND identity_key=?))
            """,venue.getId(),identity,venue.getId(),identity);
        var dates=event.starts().stream().map(EventNormalizer::date).distinct().toList();
        LocalDate date=dates.size()==1 ? dates.getFirst() : null;
        // The same event can be exposed as JSON-LD, a text list and a detail URL.
        if (previous.isEmpty() && date!=null && !EventVerification.assess(event,venue.getName()).conflicting()) {
            var sameOccurrence=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND event_date=?",venue.getId(),date)
                .stream().filter(c -> (EventMatcher.nameMatches(event.title(),(String)c.get("title")) || EventMatcher.nameMatches((String)c.get("title"),event.title()))).toList();
            if (!sameOccurrence.isEmpty()) previous=List.of(sameOccurrence.stream().min(
                Comparator.<Map<String,Object>,Integer>comparing(c -> "AUTOMATIC".equals(c.get("decision_origin")) ? 1 : 0)
                    .thenComparing(c -> (String)c.get("id"))).orElseThrow());
        }
        Map<String,Object> old=previous.isEmpty() ? null : previous.getFirst();
        // A reused official URL/UID may now advertise an entirely different party. Keep both candidates.
        if(old!=null && old.get("calendar_entry_id")!=null && !EventMatcher.nameMatches(event.title(),(String)old.get("title"))) {
            identity=EventNormalizer.hash("replacement|"+old.get("id")+"|"+EventNormalizer.text(event.title())+"|"+date);
            var replacement=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND identity_key=?",venue.getId(),identity);
            old=replacement.isEmpty() ? null : replacement.getFirst();
        }
        // Unapproved observations remain available to monitoring; they must not
        // replace the candidate's already approved source or editorial decision.
        if (old!=null && !approved && Boolean.TRUE.equals(store.jdbc().queryForObject(
                "SELECT approved FROM discovery_sources WHERE id=?",Boolean.class,old.get("source_id")))) return;
        if (old!=null) store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",venue.getId(),identity,old.get("id"));
        // Includes ambiguous legacy reviews: uncertainty about provenance never permits overwriting them.
        if (old!=null && !"AUTOMATIC".equals(old.get("decision_origin"))) {
            if (!fingerprint.equals(old.get("fingerprint")) || !EventMatcher.VERSION.equals(old.get("matcher_version"))) {
                var technical=evaluator.evaluate(event,venue.getId(),venue.getName(),approved,new DiscoveryEvaluationService.Rules(known,aliases,genres));
                store.jdbc().update("""
                    UPDATE discovery_candidates SET raw_event=?,fingerprint=?,evidence=?,source_url=?,automatic_reason=?,matching_class=?,
                    matcher_version=?,evaluated_at=now(),last_evaluation_id=?,updated_at=now() WHERE id=?
                    """,evaluator.raw(event),fingerprint,event.evidence(),url,technical.reason(),technical.classification(),EventMatcher.VERSION,operation,old.get("id"));
                store.technicalAudit((String)old.get("id"),(String)old.get("matching_class"),operation);
            }
            return;
        }
        if (old!=null && old.get("calendar_entry_id")!=null
                && (EventChangeMonitor.classify(evaluator.event(old),event,false)==null
                    || !sourceId.equals(old.get("source_id")) && compatibleFallback(evaluator.event(old),event,venue.getName())
                    && !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM event_changes WHERE candidate_id=? AND blocks_publication)",Boolean.class,old.get("id"))))) {
            store.jdbc().update("UPDATE discovery_candidates SET raw_event=?,fingerprint=?,evidence=?,source_url=?,updated_at=now() WHERE id=?",
                evaluator.raw(event),fingerprint,event.evidence(),url,old.get("id"));
            return;
        }
        if (PartyOnlyPolicy.concert(event) && old==null) return;
        if (old!=null && fingerprint.equals(old.get("fingerprint")) &&
            (old.get("calendar_entry_id")!=null || (old.get("raw_event")!=null && EventMatcher.VERSION.equals(old.get("matcher_version"))
                && !Objects.toString(old.get("reason"),"").contains("Quelle nicht")
                && !Objects.toString(old.get("reason"),"").contains("Neuer Abruf vor")))) return;
        var match=evaluator.evaluate(event,venue.getId(),venue.getName(),approved,new DiscoveryEvaluationService.Rules(known,aliases,genres));
        var times=event.starts().stream().map(EventNormalizer::time).distinct().toList();
        LocalTime time=times.size()==1 ? times.getFirst() : null;
        String status=match.status(), reason=match.reason();
        if (old!=null && old.get("calendar_entry_id")!=null) {
            status="NEEDS_REVIEW";reason="Änderung an bereits übernommenem Event: "+reason;
        }
        String id=old==null ? UUID.randomUUID().toString() : (String)old.get("id");
        String stableIdentity=old==null ? identity : (String)old.get("identity_key");
        store.jdbc().update("""
            INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,event_date,start_time,evidence,status,reason,party_id,
             matching_class,automatic_reason,matcher_version,evaluated_at,previous_status,last_evaluation_id,raw_event)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?,?) ON CONFLICT(venue_id,identity_key) DO UPDATE SET
             source_id=excluded.source_id,fingerprint=excluded.fingerprint,title=excluded.title,source_url=excluded.source_url,event_date=excluded.event_date,
             start_time=excluded.start_time,evidence=excluded.evidence,status=excluded.status,reason=excluded.reason,party_id=excluded.party_id,
             matching_class=excluded.matching_class,automatic_reason=excluded.automatic_reason,matcher_version=excluded.matcher_version,
             evaluated_at=now(),previous_status=excluded.previous_status,last_evaluation_id=excluded.last_evaluation_id,raw_event=excluded.raw_event,updated_at=now()
            """,id,venue.getId(),sourceId,stableIdentity,fingerprint,event.title(),url,date,time,event.evidence(),status,reason,match.partyId(),
             match.classification(),match.reason(),EventMatcher.VERSION,old==null ? null : old.get("status"),operation,evaluator.raw(event));
        store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",venue.getId(),identity,id);
        store.audit(id,old==null ? null : (String)old.get("status"),operation,old==null ? null : (String)old.get("matching_class"));
        if (match.partyId()!=null && date!=null && !status.equals("IGNORED")) {
            var calendar=store.jdbc().queryForList("SELECT id FROM calendar_entries WHERE location_id=? AND event_date=? AND party_id=? AND entry_type='event'",venue.getId(),date,match.partyId());
            if(calendar.size()==1) {
                int linked=store.jdbc().update("UPDATE discovery_candidates SET calendar_entry_id=?,status=?,reason=? WHERE id=? AND calendar_entry_id IS NULL",calendar.getFirst().get("id"),status.equals("DUPLICATE") ? "IMPORTED" : "NEEDS_REVIEW",status.equals("DUPLICATE") ? "Mit bestehendem Kalendereintrag verknüpft" : "Quellenänderung zu bestehendem Kalendereintrag prüfen",id);
                if(linked>0) store.audit(id,status,operation);
            }
        }
        if (old==null && operation!=null) monitor.record(id,sourceId,event,operation);
        if (status.equals("READY")) importer.importCandidate(id,match.partyId(),date,false);
    }
}
