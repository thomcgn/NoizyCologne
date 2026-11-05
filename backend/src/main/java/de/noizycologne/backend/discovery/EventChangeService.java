package de.noizycologne.backend.discovery;

import de.noizycologne.backend.calendar.*;
import de.noizycologne.backend.cancellation.CancellationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class EventChangeService {
    private final DiscoveryStore store;
    private final CancellationService cancellations;
    private final CalendarEntryRepository entries;
    private final CalendarEntryService calendar;
    private final JsonMapper json=JsonMapper.builder().build();
    public EventChangeService(DiscoveryStore store,CancellationService cancellations,CalendarEntryRepository entries,CalendarEntryService calendar) {
        this.store=store;this.cancellations=cancellations;this.entries=entries;this.calendar=calendar;
    }
    public List<Map<String,Object>> list() {
        return store.jdbc().queryForList("SELECT c.*,v.name AS venue_name,v.instagram,e.party_id AS calendar_party_id,e.location_id AS calendar_venue_id,e.event_date AS calendar_date,e.start_time AS calendar_start_time,p.name AS calendar_party_name,cv.name AS calendar_venue_name FROM event_changes c JOIN venues v ON v.id=c.venue_id LEFT JOIN calendar_entries e ON e.id=c.calendar_entry_id LEFT JOIN party_series p ON p.id=e.party_id LEFT JOIN venues cv ON cv.id=e.location_id ORDER BY c.created_at DESC LIMIT 500").stream().map(DiscoveryStore::forApi).toList();
    }
    public List<Map<String,Object>> replacements(String id) {
        return store.jdbc().queryForList("SELECT c.id,c.title FROM discovery_candidates c JOIN event_changes x ON x.venue_id=c.venue_id JOIN calendar_entries e ON e.id=x.calendar_entry_id WHERE x.id=? AND c.event_date=e.event_date AND c.id IS DISTINCT FROM x.candidate_id AND c.status NOT IN ('IGNORED','REJECTED','DUPLICATE') ORDER BY c.title LIMIT 100",id).stream().map(DiscoveryStore::forApi).toList();
    }
    public List<Map<String,Object>> history(String id) {
        return store.jdbc().queryForList("SELECT * FROM event_change_history WHERE change_id=? ORDER BY id",id).stream().map(DiscoveryStore::forApi).toList();
    }
    public record Decision(@jakarta.validation.constraints.PositiveOrZero long revision,
        @jakarta.validation.constraints.NotBlank String action,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4000) String note,
        @jakarta.validation.constraints.Size(max=12000) String dmText,
        @jakarta.validation.constraints.Size(max=4000) String answer,
        @jakarta.validation.constraints.Size(max=100) String partyId,
        @jakarta.validation.constraints.Size(max=100) String venueId,String date,String startTime,String trigger,String reasonCode,
        @jakarta.validation.constraints.Size(max=100) String replacementCandidateId) { }
    @Transactional
    public void decide(String id,Decision decision) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var rows=store.jdbc().queryForList("SELECT * FROM event_changes WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Änderung fehlt");
        var change=rows.getFirst();
        if(!(change.get("revision") instanceof Number revision) || revision.longValue()!=decision.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Änderung wurde inzwischen bearbeitet; neu laden");
        String note=Objects.toString(decision.note(),"").trim();
        if(note.isBlank()) throw new IllegalArgumentException("Begründung beziehungsweise bestätigte Antwort erforderlich");
        if(note.matches("(?is).*(diagnos|gesundheit|krank|illness|medical).*")) throw new IllegalArgumentException("Nur allgemeinen Absagegrund dokumentieren, keine Gesundheitsdetails");
        String entry=(String)change.get("calendar_entry_id");
        String state=Objects.toString(change.get("state"));boolean blocked=Boolean.TRUE.equals(change.get("blocks_publication"));
        switch(decision.action()) {
            case "CONFIRM" -> { state="CONFIRMED";blocked=true; }
            case "REJECT", "SEPARATE", "RELEASE" -> { state=decision.action();blocked=false; }
            case "DJ_ONLY" -> {
                state="CONFIRMED";blocked=true;
                store.jdbc().update("UPDATE event_changes SET kind='LINEUP_CHANGE' WHERE id=?",id);
            }
            case "INQUIRY", "SENT", "ANSWER" -> {
                String dm=Objects.toString(decision.dmText(),"");String answer=Objects.toString(decision.answer(),"");
                if((dm+answer).matches("(?is).*(diagnos|gesundheit|krank|illness|medical).*")) throw new IllegalArgumentException("Keine privaten Gesundheitsdetails in Rückfrage oder Antwort speichern");
                if(decision.action().equals("ANSWER") && answer.isBlank()) throw new IllegalArgumentException("Antwort fehlt");
                store.jdbc().update("UPDATE event_changes SET inquiry_status=?,dm_text=?,answer=? WHERE id=?",decision.action().equals("INQUIRY") ? "PREPARED" : decision.action().equals("SENT") ? "SENT" : "ANSWERED",dm,answer,id);
            }
            case "CANCEL" -> {
                if(entry==null) throw new IllegalArgumentException("Absage zuerst einem Kalendereintrag zuordnen");
                cancellations.confirm(entry,id,decision.trigger(),decision.reasonCode(),decision.replacementCandidateId());state="CONFIRMED";blocked=true;
            }
            case "UPDATE_CALENDAR" -> {
                if(entry==null) throw new IllegalArgumentException("Kein zugehöriger Kalendereintrag");
                if("POSSIBLE_REPLACEMENT".equals(change.get("kind"))) throw new IllegalArgumentException("Ersatzparty separat prüfen; alte Party-ID bleibt erhalten");
                String current=json.writeValueAsString(store.jdbc().queryForList("SELECT event_date,start_time,location_id,party_id FROM calendar_entries WHERE id=?",entry).stream().map(DiscoveryStore::forApi).toList());
                if(!current.equals(change.get("calendar_snapshot"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Kalender seit dem Quellenbefund geändert; vor einer Aktualisierung erneut vergleichen");
                var old=entries.findById(entry).orElseThrow();
                if(!Objects.equals(old.getPartyId(),decision.partyId())) throw new IllegalArgumentException("Partyreihe darf hier nicht ersetzt werden");
                calendar.updateEntry(entry,new CalendarEntryRequest(decision.date(),decision.venueId(),"event",old.getNotes(),old.getEntryPrice(),old.getPartyId(),decision.startTime(),null,old.getMentionDjIds()));
                state="RESOLVED";blocked=false;
            }
            default -> throw new IllegalArgumentException("Unbekannte Änderungsaktion");
        }
        store.jdbc().update("UPDATE event_changes SET state=?,blocks_publication=?,revision=revision+1,updated_at=now() WHERE id=?",state,blocked,id);
        store.jdbc().update("INSERT INTO event_change_history(change_id,action,note) VALUES (?,?,?)",id,decision.action(),note);
        // Clearing an alert never silently re-approves an original draft.
    }
    public String dm(String id) {
        var row=store.jdbc().queryForMap("SELECT c.*,v.name AS venue_name FROM event_changes c JOIN venues v ON v.id=c.venue_id WHERE c.id=?",id);
        var before=json.readValue((String)row.get("before_data"),EventExtractor.Event.class);
        var after=json.readValue((String)row.get("after_data"),EventExtractor.Event.class);
        var date=EventChangeMonitor.date(before);
        String day=date==null ? "den bisher genannten Termin" : date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"));
        return "Hallo liebes "+row.get("venue_name")+"-Team! 👋\n\nWir haben bei euch ursprünglich „"+before.title()+"“ für "+day+" gesehen. Jetzt finden wir „"+after.title()+"“ beziehungsweise abweichende Veranstaltungsangaben.\n\nIst die ursprüngliche Party abgesagt, verschoben oder ersetzt worden, oder finden beide Veranstaltungen statt? Könnt ihr Datum, Beginn und Location bestätigen?\n\nDanke euch und liebe Grüße\nNoizyCologne";
    }
}
