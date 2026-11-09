package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.jspecify.annotations.NonNull;
import java.util.*;

/** Recognizes catalogue names, without treating a shared series name as event identity. */
public final class PartySeriesRecognition {
    private PartySeriesRecognition() { }
    public record DetectedParty(String id, String name, String matchedName) { }
    public record Recognition(String status, List<@NonNull DetectedParty> parties) { }
    private record Hit(@NonNull DetectedParty party, int start, int end) { }

    /** An editable proposal only: mixed-case series followed by an explicitly separated uppercase line-up. */
    public static String suggestedName(String title) {
        var byDj=java.util.regex.Pattern.compile("(?iu)^(.+?)\\s+by\\s+DJ\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} &,.+'’()!/-]+)$").matcher(title.trim());
        if(byDj.matches()) return Objects.requireNonNull(byDj.group(1)).trim();
        var separator=java.util.regex.Pattern.compile("(?iu)^(.+?)\\s+(?:mit|with|feat\\.?|featuring)\\s+(.+)$").matcher(title.trim());
        if (!separator.matches()) return title.trim();
        String stem=Objects.requireNonNull(separator.group(1)).trim();
        String lineup=Objects.requireNonNull(separator.group(2)).trim();
        long letters=lineup.codePoints().filter(Character::isLetter).count();
        if (stem.codePoints().noneMatch(Character::isLowerCase) || letters<5
                || lineup.codePoints().anyMatch(Character::isLowerCase)
                || !lineup.matches("[\\p{L}\\p{N}\\s&,.+'’()!/-]+")) return title.trim();
        return stem;
    }

    public static boolean containsName(String title, String name) {
        String normalized=EventNormalizer.text(name);
        if (normalized.isBlank()) return false;
        return (" "+EventNormalizer.text(title)+" ").contains(" "+normalized+" ")
            // Preserve the previously supported whole-title/edition spellings.
            || EventMatcher.nameMatches(title,name);
    }

    public static Recognition recognize(String title,List<@NonNull PartySeries> parties,Map<String,List<String>> aliases) {
        String normalized=EventNormalizer.text(title);
        List<@NonNull Hit> hits=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for (var party:parties) {
            var names=new ArrayList<String>(); names.add(party.getName());
            names.addAll(aliases.getOrDefault(party.getId(),List.of()));
            for (String name:names) {
                if (!containsName(title,name) || !seen.add(party.getId())) continue;
                String phrase=EventNormalizer.text(name);
                int start=(" "+normalized+" ").indexOf(" "+phrase+" ");
                // Compact legacy spellings are accepted only by the strict matcher above.
                int end=start<0 ? normalized.length() : start+phrase.length();
                hits.add(new Hit(new DetectedParty(party.getId(),party.getName(),name),Math.max(0,start),end));
                break;
            }
        }
        hits.sort(Comparator.<@NonNull Hit>comparingInt(Hit::start).thenComparing(h -> h.party().id()));
        String status=hits.isEmpty() ? "NONE" : hits.size()==1 ? "SINGLE"
            : cooperation(normalized,hits) ? "COOPERATION" : "AMBIGUOUS";
        return new Recognition(status,hits.stream().map(Hit::party).toList());
    }

    private static boolean cooperation(String title,List<@NonNull Hit> hits) {
        for (int i=1;i<hits.size();i++) {
            var previous=Objects.requireNonNull(hits.get(i-1),"Vorheriger Partytreffer fehlt");
            var current=Objects.requireNonNull(hits.get(i),"Partytreffer fehlt");
            if (current.start()<previous.end()) return false;
            String connector=title.substring(previous.end(),current.start()).trim();
            if (!connector.matches("vs|versus|meets|presents|präsentiert|praesentiert|x")) return false;
        }
        return true;
    }
}
