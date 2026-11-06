package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import java.util.*;

/** Merge only scoped occurrences; keep original source observations for auditing. */
final class DiscoveryBatchMerger {
    record Input(String sourceId,EventExtractor.@NonNull Event event,boolean approved,boolean official) { }
    record Merged(Input primary,EventExtractor.@NonNull Event event,List<@NonNull Input> origins) { }
    private DiscoveryBatchMerger() { }
    static List<@NonNull Merged> merge(List<@NonNull Input> inputs,String venue) {
        List<List<@NonNull Input>> groups=new ArrayList<>();
        for (var input:inputs) {
            var related=groups.stream().filter(group -> group.stream().anyMatch(other -> related(input,other,venue))).toList();
            List<@NonNull Input> group=new ArrayList<>();
            for (var previous:related) {group.addAll(previous);groups.remove(previous);}
            group.add(input);
            groups.add(group);
        }
        List<@NonNull Merged> result=new ArrayList<>();
        for (var group:groups) {
            // Stable sort retains configured priority among equally trusted sources.
            var ordered=group.stream().sorted(Comparator.<@NonNull Input>comparingInt(i -> i.approved() ? 0 : 1)
                .thenComparingInt(i -> i.official() ? 0 : 1)).toList();
            var primary=Objects.requireNonNull(ordered.getFirst());var event=primary.event();
            var starts=ordered.stream().flatMap(i -> i.event().starts().stream()).distinct().toList();
            var dates=starts.stream().map(EventNormalizer::date).filter(Objects::nonNull).distinct().toList();
            var times=starts.stream().map(EventNormalizer::time).filter(Objects::nonNull).distinct().toList();
            boolean conflict=dates.size()>1 || times.size()>1 || ordered.stream().anyMatch(i -> EventVerification.assess(i.event(),venue).conflicting());
            if (dates.size()==1 && times.size()<=1) starts=List.of(dates.getFirst()+(times.isEmpty() ? "" : "T"+times.getFirst()));
            StringBuilder states=new StringBuilder();StringBuilder evidence=new StringBuilder();
            for (var origin:ordered) {
                String state=origin.event().state();
                if (!origin.official() && EventLifecycle.signal(origin.event()).equals("CANCELLED")) state="CANCELLATION_HINT UNCONFIRMED";
                states.append(state).append(' ');
                // Reserve room for every source instead of letting a long primary text
                // consume the entire merged evidence budget. Full originals stay in observations.
                String details=origin.event().evidence();int budget=Math.max(1,7000/ordered.size());
                evidence.append("Quelle: ").append(origin.event().url()).append('\n').append(details,0,Math.min(budget,details.length())).append('\n');
            }
            if (conflict) states.append(" SOURCE_CONFLICT");
            String location=event.location().isBlank() ? ordered.stream().map(i -> i.event().location()).filter(l -> !l.isBlank()).findFirst().orElse("") : event.location();
            String text=ordered.size()==1 ? event.evidence() : evidence.substring(0,Math.min(8000,evidence.length()));
            result.add(new Merged(primary,new EventExtractor.Event(event.identity(),event.title(),event.url(),starts,text,Objects.requireNonNull(states.toString()),location,ordered.stream().map(i -> i.event().place()).reduce(EventPlace.empty(),(left,right)->Objects.requireNonNull(left).merge(right))),List.copyOf(ordered)));
        }
        return result;
    }
    private static boolean related(Input left,Input right,String venue) {
        var a=left.event();var b=right.event();
        if (!EventMatcher.nameMatches(a.title(),b.title()) && !EventMatcher.nameMatches(b.title(),a.title())) return false;
        boolean sameIdentity=!a.identity().isBlank() && a.identity().equals(b.identity());
        if (sameIdentity) return true;
        if (EventNormalizer.distinctTimedOccurrences(a,b)) return false;
        if (!locationMatches(a.location(),venue) || !locationMatches(b.location(),venue)) return false;
        // An external unscoped listing cannot lend genres to a different venue.
        if ((!left.official() && a.location().isBlank()) || (!right.official() && b.location().isBlank())) return false;
        var da=EventChangeMonitor.date(a);var db=EventChangeMonitor.date(b);
        if (da==null || db==null) return false;
        if (da.equals(db)) return true;
        return a.identity().isBlank() || b.identity().isBlank();
    }
    static boolean locationMatches(String location,String venue) {
        return location.isBlank() || (" "+EventNormalizer.text(location)+" ").contains(" "+EventNormalizer.text(venue)+" ");
    }
}
