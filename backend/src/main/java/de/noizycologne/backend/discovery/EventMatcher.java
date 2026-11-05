package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class EventMatcher {
    public static final String VERSION = "party-rules-2";
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
    public Match match(EventExtractor.Event event, String venueName, List<PartySeries> parties,
                       Map<String,List<String>> aliases, Collection<String> genres, boolean approved) {
        if (PartyOnlyPolicy.concert(event)) return new Match("IGNORED",null,"Konzert statt Clubparty", "IGNORED");
        var matched=parties.stream().filter(p -> {
            var names=new ArrayList<>(aliases.getOrDefault(p.getId(),List.of())); names.add(p.getName());
            return names.stream().anyMatch(n -> nameMatches(event.title(),n));
        }).toList();
        if (matched.size()>1) return new Match("NEEDS_REVIEW",null,"Mehrere Partyreihen passen; eindeutige Zuordnung fehlt","UNVERIFIED");
        String party=matched.isEmpty() ? null : matched.get(0).getId();
        var hits=DiscoveryGenreFilter.matchingTerms(event,genres);
        if (party==null && unrelated(event))
            return new Match("IGNORED",null,"Fachfremde Veranstaltung: expliziter Nicht-Party-Beleg","IGNORED");
        String classification=party!=null ? "MATCH" : hits.isEmpty() ? "UNVERIFIED" : "POSSIBLE_MATCH";
        String reason=party!=null ? "Bekannte Partyreihe erkannt" +
                (matched.get(0).getStandardGenres().isEmpty() ? "" : "; Standardgenres: "+String.join(", ",matched.get(0).getStandardGenres()))
                : hits.isEmpty() ? "Nicht genügend Informationen zur Genrezuordnung; fehlende Angaben sind keine Ablehnung"
                : "Partyreihe unbekannt, aber musikalisch passend; Genre-Match: "+String.join(", ",hits);
        if (!event.location().isBlank() && !EventNormalizer.text(event.location()).contains(EventNormalizer.text(venueName)))
            return new Match("NEEDS_REVIEW",party,reason+"; abweichende Location in der Quelle",classification);
        Set<java.time.LocalDate> dates=new HashSet<>();
        for (String raw:event.starts()) {
            var date=EventNormalizer.date(raw);
            if (date==null) return new Match("NEEDS_INQUIRY",party,reason+"; Startdatum fehlt oder ist unvollständig",classification);
            dates.add(date);
        }
        if (dates.size()!=1) return new Match("NEEDS_INQUIRY",party,reason+"; Datum fehlt oder ist widersprüchlich: "+event.starts(),classification);
        if (EventNormalizer.text(event.state()).matches(".*(cancel|reschedul|postpon|unconfirm|tentative).*") )
            return new Match("NEEDS_INQUIRY",party,reason+"; Absage, Verschiebung oder unbestätigte Angabe: "+event.state(),classification);
        if (party==null) return new Match(classification.equals("UNVERIFIED") ? "NEEDS_INQUIRY" : "NEEDS_REVIEW",null,reason,classification);
        if (!approved) return new Match("NEEDS_REVIEW",party,reason+"; Quelle nicht zur automatischen Übernahme freigegeben",classification);
        return new Match("READY",party,reason+"; eindeutiges Startdatum und freigegebene Quelle",classification);
    }
}
