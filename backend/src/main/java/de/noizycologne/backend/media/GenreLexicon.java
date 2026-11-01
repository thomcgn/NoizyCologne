package de.noizycologne.backend.media;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class GenreLexicon {
    private static final Map<String, List<String>> ENTRIES = new LinkedHashMap<>();
    static {
        add("Rock", "rock"); add("Alternative", "alternative"); add("Indie", "indie"); add("Punk", "punk");
        add("Punkrock", "punkrock", "punk rock"); add("Garage Rock", "garage rock", "garage-rock");
        add("Garage-Punk", "garage punk", "garage-punk", "garagepunk");
        add("Rock’n’Roll", "rock'n'roll", "rock n roll", "rock'n roll", "rock and roll", "rnr", "r&r");
        add("50s Rock’n’Roll", "50s rnr", "50's rnr", "50s rock n roll");
        add("Wild R&B", "wild rnb", "wild r&b", "wild rhythm and blues");
        add("Rockabilly", "rockabilly"); add("Psychobilly", "psychobilly"); add("Emo", "emo"); add("Hardcore", "hardcore");
        add("Metal", "metal"); add("Metalcore", "metalcore", "metal core"); add("Heavy Metal", "heavy metal");
        add("Thrash Metal", "thrash metal", "thrash-metal"); add("Death Metal", "death metal", "death-metal");
        add("Black Metal", "black metal", "black-metal"); add("Doom", "doom"); add("Hard Rock", "hard rock", "hard-rock");
        add("Psych", "psych", "psychedelic"); add("Ska", "ska"); add("Soul", "soul"); add("Northern Soul", "northern soul");
        add("Popcorn", "popcorn"); add("Rhythm & Blues", "rhythm and blues", "rhythm & blues", "rnb");
        add("Post-Punk", "post-punk", "post punk"); add("New Wave", "new wave"); add("Darkwave", "darkwave", "dark wave");
        add("Gothic Rock", "gothic rock"); add("Britpop", "britpop");
        add("Singer-Songwriter", "singer-songwriter", "singer songwriter"); add("Folk", "folk"); add("Pop", "pop");
        add("Sonstiges", "sonstiges", "other");
    }

    private static void add(String canonical, String... aliases) { ENTRIES.put(canonical, List.of(aliases)); }

    public List<String> findGenres(List<TesseractOcrService.OcrWord> words) {
        String text = normalize(words.stream().map(word -> word.text()).reduce("", (a, b) -> a + " " + b));
        Map<String, Boolean> found = new LinkedHashMap<>();
        ENTRIES.entrySet().stream()
                .sorted((left, right) -> Integer.compare(longestAlias(right.getValue()), longestAlias(left.getValue())))
                .forEach(entry -> entry.getValue().stream().map(this::normalize)
                        .filter(alias -> contains(text, alias)).findFirst()
                        .ifPresent(alias -> found.put(entry.getKey(), true)));
        // Specific compound genres are evidence for the compound label, not an
        // additional generic label. This avoids turning "Garage-Punk" into
        // the misleading pair "Garage-Punk" + "Punk".
        if (found.containsKey("Garage-Punk")) found.remove("Punk");
        if (found.containsKey("Garage Rock")) found.remove("Rock");
        if (found.containsKey("50s Rock’n’Roll")) found.remove("Rock’n’Roll");
        if (found.containsKey("Wild R&B")) found.remove("Rhythm & Blues");
        return List.copyOf(found.keySet());
    }

    private int longestAlias(List<String> aliases) {
        return aliases.stream().mapToInt(alias -> normalize(alias).length()).max().orElse(0);
    }

    String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("[‐‑‒–—−]", "-").replaceAll("[’'`´]", "'")
                .replaceAll("[&]", " and ").replaceAll("[^\\p{L}\\p{N}']+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean contains(String text, String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }
}
