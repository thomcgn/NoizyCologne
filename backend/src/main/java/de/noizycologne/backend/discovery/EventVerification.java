package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/** Quality of the extracted occurrence, independent of series identity and lifecycle. */
public final class EventVerification {
    private EventVerification() { }
    public record Result(String status,List<@NonNull String> reasons,@Nullable LocalDate date) {
        public boolean sufficient() { return status.equals("VERIFIED"); }
        public boolean conflicting() { return status.equals("CONFLICTING_DATA"); }
    }
    public static Result assess(EventExtractor.Event event,String venueName) {
        List<@NonNull String> missing=new ArrayList<>();
        List<@NonNull String> conflicts=new ArrayList<>();
        List<@NonNull String> speculative=new ArrayList<>();
        String state=EventNormalizer.text(event.state());
        if (EventNormalizer.text(event.title()).isBlank()) missing.add("Auswertbarer Veranstaltungstitel fehlt");
        if (state.contains("rohdaten unvollständig") || state.contains("legacy incomplete"))
            missing.add("Rohdaten der Quelle sind unvollständig; erneuter Abruf erforderlich");
        Set<LocalDate> days=new HashSet<>(); Set<LocalTime> times=new HashSet<>();
        for (String raw:event.starts()) {
            var date=EventNormalizer.date(raw);
            if (date==null) missing.add("Startdatum fehlt oder ist unvollständig"); else days.add(date);
            var time=EventNormalizer.time(raw);
            if (time!=null) times.add(time);
        }
        if (days.isEmpty()) missing.add("Vollständiges Startdatum fehlt");
        if (days.size()>1) conflicts.add("Widersprüchliche Starttage in den Quellenangaben");
        if (times.size()>1) conflicts.add("Widersprüchliche Startzeiten in den Quellenangaben");
        String venue=EventNormalizer.text(venueName), location=EventNormalizer.text(event.location());
        if (!venue.isBlank() && !location.isBlank() && !(" "+location+" ").contains(" "+venue+" "))
            conflicts.add("Abweichende Location in der Quelle");
        if (state.matches(".*\\b(source conflict|data conflict|location widerspruch|abweichende location)\\b.*"))
            conflicts.add("Widersprüchliche Quellen- oder Locationangabe");
        if (state.matches(".*\\b(tentative|speculative|speculative timezone|termin unbestätigt|unbestätigter termin|unbekannte zeitzone)\\b.*")
                || state.matches(".*\\bfreitag (oder|und) samstag\\b.*"))
            speculative.add("Terminangaben sind vorläufig oder nicht zuverlässig interpretierbar");
        LocalDate day=days.size()==1 ? days.iterator().next() : null;
        if (!conflicts.isEmpty()) return new Result("CONFLICTING_DATA",List.copyOf(conflicts),day);
        if (!missing.isEmpty()) return new Result("MISSING_DATA",missing.stream().distinct().toList(),day);
        if (!speculative.isEmpty()) return new Result("SPECULATIVE",List.copyOf(speculative),day);
        return new Result("VERIFIED",List.of("Veranstaltungstitel und eindeutiger Starttag sind auswertbar"),day);
    }
}
