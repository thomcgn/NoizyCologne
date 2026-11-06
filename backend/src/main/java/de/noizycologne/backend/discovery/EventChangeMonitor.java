package de.noizycologne.backend.discovery;

import de.noizycologne.backend.json.JsonValues;
import de.noizycologne.backend.venue.Venue;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.NonNull;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

/** Observations belong to existing candidates; alerts never silently edit calendar or editorial data. */
@Service
public class EventChangeMonitor {
    public record Observation(String sourceId, EventExtractor.@NonNull Event event, boolean official) { }
    private final DiscoveryStore store;
    private final DiscoveryEvaluationService evaluator;
    private final JsonMapper json=JsonMapper.builder().build();
    public EventChangeMonitor(DiscoveryStore store,DiscoveryEvaluationService evaluator) { this.store=store;this.evaluator=evaluator; }
    public static String identity(EventExtractor.Event event) {
        return EventNormalizer.hash(event.identity().isBlank() ? EventNormalizer.text(event.title())+"|"+event.starts() : event.identity());
    }
    public static LocalDate date(EventExtractor.Event event) {
        var dates=event.starts().stream().map(EventNormalizer::date).distinct().toList();
        return dates.size()==1 ? dates.getFirst() : null;
    }
    private static String normalizedStart(String input) {
        var date=EventNormalizer.date(input);var time=EventNormalizer.time(input);
        return date==null ? input : date.toString()+(time==null ? "" : "T"+time);
    }
    public static String fingerprint(EventExtractor.Event event) {
        return EventNormalizer.hash(EventNormalizer.text(event.title())+"|"+event.starts().stream().map(EventChangeMonitor::normalizedStart).sorted().distinct().toList()
            +"|"+EventNormalizer.text(event.location())+"|"+Arrays.stream(EventNormalizer.text(event.state()).split(" ")).distinct().sorted().toList()+"|"+EventNormalizer.text(event.evidence()));
    }
    private Set<String> keys(Map<String,Object> row) {
        var keys=new HashSet<>(store.jdbc().queryForList("SELECT identity_key FROM discovery_candidate_identities WHERE candidate_id=?",String.class,row.get("id")));
        if(row.get("identity_key") instanceof String primary) keys.add(primary);
        return keys;
    }
    private boolean matches(Set<String> keys,EventExtractor.Event old,EventExtractor.Event event) {
        String key=identity(event);
        if(keys.contains(key)) return true;
        // No title-only matching across dates: repetition and a move cannot be distinguished safely.
        return Objects.equals(date(old),date(event)) && date(old)!=null
            && (EventMatcher.nameMatches(event.title(),old.title()) || EventMatcher.nameMatches(old.title(),event.title()));
    }
    @Transactional
    public void batch(Venue venue,List<@NonNull Observation> observations,String operation) { batch(venue,observations,operation,false); }
    @Transactional
    public void batch(Venue venue,List<@NonNull Observation> observations,String operation,boolean horizonOnly) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        observations=observations.stream().map(o -> new Observation(o.sourceId(),EventPrivacy.safe(o.event()),o.official())).toList();
        List<Map<String,Object>> candidates=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND (calendar_entry_id IS NOT NULL OR status NOT IN ('IGNORED','REJECTED','DUPLICATE'))",venue.getId());
        List<Map<String,Object>> calendars=store.jdbc().queryForList("SELECT e.*,p.name AS party_name FROM calendar_entries e JOIN party_series p ON p.id=e.party_id WHERE e.location_id=? AND e.entry_type='event' AND e.event_status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM discovery_candidates c WHERE c.calendar_entry_id=e.id)",venue.getId());
        if(horizonOnly) {
            var today=LocalDate.now(ZoneId.of("Europe/Berlin"));
            candidates=candidates.stream().filter(row -> inHorizon(row,today)).toList();
            calendars=calendars.stream().filter(row -> inHorizon(row,today)).toList();
        }
        for(var entry:calendars) {
            var calendarDay=entry.get("event_date");
            if(calendarDay==null) throw new IllegalStateException("Kalendereintrag ohne Datum");
            String start=calendarDay.toString()+(entry.get("start_time")==null ? "" : "T"+entry.get("start_time"));
            var old=new EventExtractor.Event("calendar:"+entry.get("id"),(String)entry.get("party_name"),venue.getWebsiteUrl(),List.of(start),"Bestätigter Kalendereintrag", "",venue.getName());
            boolean present=observations.stream().filter(Observation::official).anyMatch(o -> Objects.equals(date(old),date(o.event())) && EventMatcher.nameMatches(o.event().title(),old.title()));
            Map<String,Object> reference=new HashMap<>();reference.put("calendar_entry_id",entry.get("id"));
            if(present) {
                for(var found:observations) if(found.official() && Objects.equals(date(old),date(found.event())) && EventMatcher.nameMatches(found.event().title(),old.title())) {
                    String kind=classify(old,found.event(),false);
                    if(kind!=null && !kind.equals("EVENT_CHANGED")) alert(reference,venue,kind,old,found.event(),sourceUrl(found.sourceId()),"Geänderter offizieller Befund zu bestehendem Kalendereintrag");
                }
                continue;
            }
            for(var found:observations) if(found.official() && Objects.equals(date(old),date(found.event())) && !PartyOnlyPolicy.concert(found.event()))
                alert(reference,venue,"POSSIBLE_REPLACEMENT",old,found.event(),sourceUrl(found.sourceId()),"Andere Party am belegten Kalendertag; getrennte Veranstaltungen oder Ersatz prüfen");
        }
        for (var candidate:candidates) {
            var old=baseline(candidate);
            var keys=keys(candidate);
            var matching=observations.stream().filter(o -> matches(keys,old,o.event())).toList();
            var officialMatches=matching.stream().filter(Observation::official).toList();
            var dates=officialMatches.stream().flatMap(o -> o.event().starts().stream()).map(EventNormalizer::date).filter(Objects::nonNull).distinct().toList();
            var times=officialMatches.stream().flatMap(o -> o.event().starts().stream()).map(EventNormalizer::time).filter(Objects::nonNull).distinct().toList();
            if(dates.size()>1 || times.size()>1) {
                var representative=Objects.requireNonNull(officialMatches.getFirst(), "Offizieller Quellenbefund fehlt");
                var event=representative.event();
                var conflicting=new EventExtractor.Event(event.identity(),event.title(),event.url(),officialMatches.stream().flatMap(o -> o.event().starts().stream()).distinct().sorted().toList(),officialMatches.stream().map(o -> sourceUrl(o.sourceId())+": "+o.event().starts()).distinct().sorted().collect(java.util.stream.Collectors.joining("\n")), "SOURCE_CONFLICT",event.location());
                alert(candidate,venue,"CONFLICTING_SOURCES",old,conflicting,sourceUrl(representative.sourceId()),"Offizielle Quellen nennen widersprüchliche Datums-/Zeitangaben");
                for(var found:officialMatches) observation((String)candidate.get("id"),found.sourceId(),found.event(),operation);
                continue;
            }
            for (var found:matching) {
                var event=found.event();
                var history=store.jdbc().queryForList("SELECT event_data FROM discovery_observations WHERE candidate_id=? AND source_id=? ORDER BY observed_at DESC,id DESC LIMIT 1",String.class,candidate.get("id"),found.sourceId());
                var sourceBefore=history.isEmpty() ? old : JsonValues.readRequired(json,Objects.requireNonNull(history.getFirst()),EventExtractor.Event.class);
                boolean unchanged=!history.isEmpty() && fingerprint(sourceBefore).equals(fingerprint(event));
                boolean supplement=history.isEmpty() && compatibleSupplement(old,event,venue.getName());
                if (unchanged || supplement) {
                    rememberOrigin((String)candidate.get("id"),found.sourceId(),event,operation);
                    continue;
                }
                if (!found.official()) {
                    observation((String)candidate.get("id"),found.sourceId(),event,operation);
                    if (!fingerprint(old).equals(fingerprint(event))) alert(candidate,venue,"CONFLICTING_SOURCES",old,event,sourceUrl(found.sourceId()),"Abweichende nicht-offizielle Quelle; Bestätigung erforderlich");
                    continue;
                }
                String kind=classify(sourceBefore,event,false);
                if (kind!=null) alert(candidate,venue,kind,old,event,sourceUrl(found.sourceId()),"Offizieller Quellenbefund weicht ab: "+kind);
                if("POSSIBLE_REPLACEMENT".equals(kind)) observation((String)candidate.get("id"),found.sourceId(),event,operation);
                else record((String)candidate.get("id"),found.sourceId(),event,operation);
            }
            if (matching.stream().anyMatch(Observation::official)) continue;
            for (var found:observations) {
                if (!found.official() || PartyOnlyPolicy.concert(found.event()) || date(old)==null || date(found.event())==null) continue;
                if(!Objects.equals(date(old),date(found.event()))) {
                    if(EventMatcher.nameMatches(found.event().title(),old.title()) || EventMatcher.nameMatches(old.title(),found.event().title()))
                        alert(candidate,venue,"CONFLICTING_SOURCES",old,found.event(),sourceUrl(found.sourceId()),"Ohne stabile externe Identität anderes Datum: Wiederholung oder Verschiebung redaktionell prüfen");
                    continue;
                }
                if (!EventNormalizer.text(old.title()).equals(EventNormalizer.text(found.event().title())))
                    alert(candidate,venue,"POSSIBLE_REPLACEMENT",old,found.event(),sourceUrl(found.sourceId()),"Bisheriges Event nicht bestätigt; andere Party an derselben Location und demselben Tag. Mehrere Veranstaltungen sind möglich.");
            }
        }
    }
    private boolean compatibleSupplement(EventExtractor.Event old,EventExtractor.Event event,String venue) {
        return Objects.equals(date(old),date(event)) && date(old)!=null
            && (EventMatcher.nameMatches(old.title(),event.title()) || EventMatcher.nameMatches(event.title(),old.title()))
            && DiscoveryBatchMerger.locationMatches(event.location(),venue)
            && EventLifecycle.signal(old).equals("ACTIVE") && EventLifecycle.signal(event).equals("ACTIVE")
            && !EventVerification.assess(event,venue).conflicting()
            && (old.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).findFirst().isEmpty()
                || event.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).findFirst().isEmpty()
                || old.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).findFirst()
                    .equals(event.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).findFirst()));
    }
    @Transactional
    public void rememberOrigin(String candidate,String source,EventExtractor.Event event,String operation) {
        observation(candidate,source,EventPrivacy.safe(event),operation);
        store.jdbc().update("INSERT INTO discovery_source_checks(candidate_id,source_id,consecutive_missing,last_operation) VALUES (?,?,0,?) ON CONFLICT(candidate_id,source_id) DO UPDATE SET consecutive_missing=0,last_operation=excluded.last_operation",candidate,source,operation);
    }
    private boolean inHorizon(Map<String,Object> row,LocalDate today) {
        Object value=row.get("calendar_entry_id")==null ? row.get("event_date") : store.jdbc().queryForObject("SELECT event_date FROM calendar_entries WHERE id=?",LocalDate.class,row.get("calendar_entry_id"));
        LocalDate date=value instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : value instanceof LocalDate day ? day : null;
        return date!=null && !date.isBefore(today) && !date.isAfter(today.plusDays(21));
    }
    private EventExtractor.Event baseline(Map<String,Object> candidate) {
        Object raw=candidate.get("monitored_event");
        return raw instanceof String value && !value.isBlank() ? JsonValues.readRequired(json,value,EventExtractor.Event.class) : evaluator.event(candidate);
    }
    /** Explicit state tokens avoid turning a DJ's illness mentioned in prose into an event cancellation. */
    public static String classify(EventExtractor.Event old,EventExtractor.Event event,boolean conflict) {
        var verification=EventVerification.assess(event, "");
        if (conflict || verification.conflicting()) return "CONFLICTING_SOURCES";
        String lifecycle=EventLifecycle.signal(event);
        if (lifecycle.equals("CANCELLED")) return "CANCELLATION_DETECTED";
        if (fingerprint(old).equals(fingerprint(event)) || sameMeaning(old,event)) return null;
        if (lifecycle.equals("LINEUP_CHANGE")) return "LINEUP_CHANGE";
        if (lifecycle.equals("POSTPONED") || lifecycle.equals("RESCHEDULED")) return "RESCHEDULE_DETECTED";
        if (!Objects.equals(date(old),date(event)) && date(old)!=null && date(event)!=null) return "RESCHEDULE_DETECTED";
        if (lifecycle.equals("CANCELLATION_UNCONFIRMED")) return "CANCELLATION_UNCONFIRMED";
        if (!verification.sufficient()) return "CONFLICTING_SOURCES";
        if (!EventNormalizer.text(old.title()).equals(EventNormalizer.text(event.title())) && Objects.equals(date(old),date(event))
            && !EventMatcher.nameMatches(event.title(),old.title()) && !EventMatcher.nameMatches(old.title(),event.title())) return "POSSIBLE_REPLACEMENT";
        if (!fingerprint(old).equals(fingerprint(event))) return "EVENT_CHANGED";
        return null;
    }
    /** Technical confirmation labels alone do not change an otherwise identical event. */
    private static boolean sameMeaning(EventExtractor.Event old,EventExtractor.Event event) {
        return EventNormalizer.text(old.title()).equals(EventNormalizer.text(event.title()))
            && old.starts().stream().map(EventChangeMonitor::normalizedStart).sorted().distinct().toList()
                .equals(event.starts().stream().map(EventChangeMonitor::normalizedStart).sorted().distinct().toList())
            && EventNormalizer.text(old.location()).equals(EventNormalizer.text(event.location()))
            && EventNormalizer.text(old.evidence()).equals(EventNormalizer.text(event.evidence()))
            && EventLifecycle.signal(old).equals(EventLifecycle.signal(event))
            && EventVerification.assess(old, "").status().equals(EventVerification.assess(event, "").status());
    }
    @Transactional
    public void record(String candidate,String source,EventExtractor.Event event,String operation) {
        observation(candidate,source,event,operation);
        if(!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM discovery_sources WHERE id=? AND source_type='OFFICIAL')",Boolean.class,source))) return;
        store.jdbc().update("UPDATE discovery_candidates SET last_confirmed_at=now(),last_changed_at=CASE WHEN monitor_fingerprint IS DISTINCT FROM ? THEN now() ELSE last_changed_at END,monitored_event=?,monitor_fingerprint=? WHERE id=?",fingerprint(event),evaluator.raw(event),fingerprint(event),candidate);
        store.jdbc().update("INSERT INTO discovery_source_checks(candidate_id,source_id,consecutive_missing,last_operation) VALUES (?,?,0,?) ON CONFLICT(candidate_id,source_id) DO UPDATE SET consecutive_missing=0,last_operation=excluded.last_operation",candidate,source,operation);
    }
    private void observation(String candidate,String source,EventExtractor.Event event,String operation) {
        store.jdbc().update("INSERT INTO discovery_observations(candidate_id,source_id,operation_id,fingerprint,event_data) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",candidate,source,operation,fingerprint(event),evaluator.raw(event));
    }
    @Transactional
    public void missing(Venue venue,String source,List<EventExtractor.@NonNull Event> events,String operation,boolean complete) {
        if (!complete) return;
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var rows=store.jdbc().queryForList("SELECT c.* FROM discovery_candidates c WHERE c.venue_id=? AND EXISTS(SELECT 1 FROM discovery_observations o WHERE o.candidate_id=c.id AND o.source_id=?) AND (c.calendar_entry_id IS NOT NULL OR c.status NOT IN ('IGNORED','REJECTED','DUPLICATE'))",venue.getId(),source);
        for(var row:rows) {
            var old=baseline(row);
            var day=date(old);
            var today=LocalDate.now(ZoneId.of("Europe/Berlin"));
            if (day==null || day.isBefore(today) || day.isAfter(today.plusDays(21))) continue;
            var keys=keys(row);
            if (events.stream().anyMatch(event -> matches(keys,old,event))) continue;
            store.jdbc().update("INSERT INTO discovery_source_checks(candidate_id,source_id,consecutive_missing,last_operation) VALUES (?,?,1,?) ON CONFLICT(candidate_id,source_id) DO UPDATE SET consecutive_missing=discovery_source_checks.consecutive_missing+1,last_operation=excluded.last_operation WHERE discovery_source_checks.last_operation IS DISTINCT FROM excluded.last_operation",row.get("id"),source,operation);
            Integer count=store.jdbc().queryForObject("SELECT consecutive_missing FROM discovery_source_checks WHERE candidate_id=? AND source_id=?",Integer.class,row.get("id"),source);
            if (count!=null && count>=2) alert(row,venue,"SOURCE_MISSING",old,old,sourceUrl(source),"Bei zwei vollständigen Abrufen derselben Quelle nicht mehr bestätigt. Keine bestätigte Absage.");
        }
    }
    private String sourceUrl(String source) { return Objects.requireNonNull(store.jdbc().queryForObject("SELECT url FROM discovery_sources WHERE id=?",String.class,source)); }
    private void alert(Map<String,Object> candidate,Venue venue,String kind,EventExtractor.Event old,EventExtractor.Event event,String url,String reason) {
        String hash=EventNormalizer.hash(Objects.toString(candidate.get("id"),Objects.toString(candidate.get("calendar_entry_id")))+"|"+kind+"|"+(kind.equals("CANCELLATION_DETECTED") ? "" : fingerprint(old))+"|"+fingerprint(event));
        String id=UUID.randomUUID().toString();
        String snapshot=json.writeValueAsString(store.jdbc().queryForList("SELECT event_date,start_time,location_id,party_id FROM calendar_entries WHERE id=?",candidate.get("calendar_entry_id")).stream().map(DiscoveryStore::forApi).toList());
        int created=store.jdbc().update("INSERT INTO event_changes(id,candidate_id,calendar_entry_id,venue_id,kind,before_data,after_data,source_url,reason,fingerprint,calendar_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,candidate.get("id"),candidate.get("calendar_entry_id"),venue.getId(),kind,evaluator.raw(old),evaluator.raw(event),url,reason,hash,snapshot);
        if (created>0) {
            store.jdbc().update("INSERT INTO event_change_history(change_id,action,note) VALUES (?,'DETECTED',?)",id,reason);
            store.jdbc().update("UPDATE editorial_post_drafts SET approval_needs_review=true WHERE calendar_entry_id=?",candidate.get("calendar_entry_id"));
        }
    }
}
