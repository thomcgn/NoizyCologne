package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class EventMatcher {
    public static final String VERSION = "party-rules-4";
    public record Match(String status, String partyId, String reason, String classification) { }
    public static boolean nameMatches(String title, String name) {
        String t=EventNormalizer.text(title), n=EventNormalizer.text(name);
        if (n.isBlank()) return false;
        if (t.replace(" ","").equals(n.replace(" ",""))) return true;
        // Only explicit edition suffixes; never arbitrary title substrings or fuzzy scores.
        for (int boundary=t.indexOf(' ');boundary>0;boundary=t.indexOf(' ',boundary+1)) {
            if (t.substring(0,boundary).replace(" ","").equals(n.replace(" ","")) && t.substring(boundary+1).matches(
                "(?:(?:halloween|christmas|weihnachts|silvester|birthday|geburtstags|sommer|summer|winter) )?(?:special|edition)(?: [\\p{L}\\p{N} ]+)?|(?:vol|volume|ausgabe) \\d+")) return true;
        }
        return false;
    }
    static boolean unrelated(EventExtractor.Event event) {
        return EventNormalizer.text(event.title()+" "+event.evidence()).matches(
            ".*\\b(poetry slam|comedy|stand up|lesung|reisevortrag|geschlossene gesellschaft|pitchen statt swipen|ausschließlich kölsche musik)\\b.*");
    }
    public Match match(EventExtractor.Event event, String venueName, List<@NonNull PartySeries> parties,
                       Map<String,List<String>> aliases, Collection<String> genres, boolean approved) {
        if (PartyOnlyPolicy.concert(event)) return new Match("IGNORED",null,"Konzert statt Clubparty", "IGNORED");
        var recognition=PartySeriesRecognition.recognize(event.title(),parties,aliases);
        if (recognition.parties().size()>1) {
            String names=String.join(", ",recognition.parties().stream().map(PartySeriesRecognition.DetectedParty::name).toList());
            return new Match("NEEDS_REVIEW",null,
                (recognition.status().equals("COOPERATION") ? "Kooperation erkannt: " : "Mehrere bekannte Partyreihen erkannt: ")+names
                +"; der Kalender unterstützt eine Partyreihe pro Termin; manuelle Zuordnung erforderlich","UNVERIFIED");
        }
        String party=recognition.parties().isEmpty() ? null : Objects.requireNonNull(recognition.parties().getFirst(),"Partytreffer fehlt").id();
        var matched=parties.stream().filter(p -> p.getId().equals(party)).findFirst();
        String standardGenres="";
        if (party!=null) {
            var matchedParty=Objects.requireNonNull(matched.orElseThrow(),"Erkannte Partyreihe fehlt");
            if (!matchedParty.getStandardGenres().isEmpty()) standardGenres="; Standardgenres: "+String.join(", ",matchedParty.getStandardGenres());
        }
        var hits=DiscoveryGenreFilter.matchingTerms(event,genres);
        if (party==null && unrelated(event))
            return new Match("IGNORED",null,"Fachfremde Veranstaltung: expliziter Nicht-Party-Beleg","IGNORED");
        String classification=party!=null ? "MATCH" : hits.isEmpty() ? "UNVERIFIED" : "POSSIBLE_MATCH";
        String reason=party!=null ? "Bekannte Partyreihe erkannt"+standardGenres
                : hits.isEmpty() ? "Nicht genügend Informationen zur Genrezuordnung; fehlende Angaben sind keine Ablehnung"
                : "Partyreihe unbekannt, aber musikalisch passend; Genre-Match: "+String.join(", ",hits);
        var verification=EventVerification.assess(event,venueName);
        if (!verification.sufficient())
            return new Match("NEEDS_INQUIRY",party,reason+"; "+String.join("; ",verification.reasons()),classification);
        String lifecycle=EventLifecycle.signal(event);
        if (!lifecycle.equals("ACTIVE"))
            return new Match("NEEDS_INQUIRY",party,reason+"; Veranstaltungsänderung prüfen: "+lifecycle,classification);
        if (party==null) return new Match(classification.equals("UNVERIFIED") ? "NEEDS_INQUIRY" : "NEEDS_REVIEW",null,reason,classification);
        if (!approved) return new Match("NEEDS_REVIEW",party,reason+"; Quelle nicht zur automatischen Übernahme freigegeben",classification);
        return new Match("READY",party,reason+"; eindeutiges Startdatum und freigegebene Quelle",classification);
    }
}
