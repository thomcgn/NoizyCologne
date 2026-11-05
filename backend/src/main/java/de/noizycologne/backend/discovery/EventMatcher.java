package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class EventMatcher {
    public record Match(String status, String partyId, String reason) { }
    public Match match(EventExtractor.Event event, String venueName, List<PartySeries> parties,
                       Map<String, List<String>> aliases, Collection<String> genres, boolean approved) {
        String title = EventNormalizer.text(event.title());
        var matched = parties.stream().filter(p -> {
            var names = new ArrayList<>(aliases.getOrDefault(p.getId(), List.of())); names.add(p.getName());
            return names.stream().map(EventNormalizer::text).anyMatch(n -> !n.isBlank() && title.equals(n));
        }).toList();
        String body = EventNormalizer.text(event.title() + " " + event.evidence());
        boolean partyCharacter = body.matches(".*\\b(party|partys|parties|clubnacht|dancefloor|disco|dj|djs)\\b.*");
        boolean relevantGenre = genres.stream().map(EventNormalizer::text).filter(g -> !g.isBlank())
                .anyMatch(g -> (" " + body + " ").contains(" " + g + " "));
        if (matched.isEmpty() && (!partyCharacter || !relevantGenre)) return new Match("IGNORED", null, "Kein Partyreihen-Treffer; Partycharakter und passendes Kataloggenre nicht gemeinsam belegt");
        String party = matched.size() == 1 ? matched.get(0).getId() : null;
        if (matched.size() > 1) return new Match("NEEDS_REVIEW", null, "Mehrere Partyreihen passen");
        if (!event.location().isBlank() && !EventNormalizer.text(event.location()).contains(EventNormalizer.text(venueName)))
            return new Match("NEEDS_REVIEW", party, "Abweichende Location in der Quelle");
        Set<java.time.LocalDate> dates = new HashSet<>();
        for (String raw : event.starts()) {
            var date = EventNormalizer.date(raw);
            if (date == null) return new Match("NEEDS_INQUIRY", party, "Startdatum fehlt oder ist unvollständig");
            dates.add(date);
        }
        if (dates.size() != 1) return new Match("NEEDS_INQUIRY", party, "Startdatum fehlt oder ist widersprüchlich: " + event.starts());
        String state = event.state().toLowerCase(Locale.ROOT);
        if (state.matches(".*(cancel|reschedul|postpon|unconfirm|tentative).*") || state.contains("unconfirmed"))
            return new Match("NEEDS_INQUIRY", party, "Absage, Verschiebung oder unbestätigte Angabe: " + event.state());
        if (party == null) return new Match("NEEDS_REVIEW", null, "Neue Party; Partycharakter und Kataloggenre belegt, Partyreihe zuordnen");
        if (!approved) return new Match("NEEDS_REVIEW", party, "Quelle nicht zur automatischen Übernahme freigegeben");
        return new Match("READY", party, "Exakter Partyreihenname oder expliziter Alias; eindeutiges Startdatum");
    }
}
