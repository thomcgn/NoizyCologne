package de.noizycologne.backend.media;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        return findGenreMatches(words).stream().map(match -> match.genre()).toList();
    }

    public List<GenreMatch> findGenreMatches(List<TesseractOcrService.OcrWord> words) {
        List<TextLine> lines = lines(words);
        String text = normalize(lines.stream().map(line -> line.text()).collect(Collectors.joining(" ")));
        Map<String, GenreMatch> found = new LinkedHashMap<>();
        ENTRIES.entrySet().stream()
                .sorted((left, right) -> Integer.compare(longestAlias(right.getValue()), longestAlias(left.getValue())))
                .forEach(entry -> {
                    GenreMatch match = bestMatch(entry.getKey(), entry.getValue(), text, lines);
                    if (match != null) found.put(entry.getKey(), match);
                });
        // Specific compound genres are evidence for the compound label, not an
        // additional generic label. This avoids turning "Garage-Punk" into
        // the misleading pair "Garage-Punk" + "Punk".
        if (found.containsKey("Garage-Punk")) found.remove("Punk");
        if (found.containsKey("Garage Rock")) found.remove("Rock");
        if (found.containsKey("50s Rock’n’Roll")) found.remove("Rock’n’Roll");
        if (found.containsKey("Wild R&B")) found.remove("Rhythm & Blues");
        return List.copyOf(found.values());
    }

    private GenreMatch bestMatch(String genre, List<String> aliases, String text, List<TextLine> lines) {
        GenreMatch best = null;
        for (String aliasValue : aliases) {
            String alias = normalize(aliasValue);
            List<String> aliasTokens = List.of(alias.split(" "));
            if (contains(text, alias)) {
                double evidence = evidenceConfidence(aliasTokens, lines);
                best = better(best, new GenreMatch(genre, evidence, false));
                continue;
            }
            // Short words such as Ska are too ambiguous for automatic fuzzy correction.
            if (alias.length() < 6 || aliasTokens.stream().anyMatch(token -> token.length() < 3)) continue;
            for (TextLine line : lines) {
                List<String> tokens = line.words().stream().map(word -> normalize(word.text())).toList();
                int windowSize = aliasTokens.size();
                for (int start = 0; start + windowSize <= tokens.size(); start++) {
                    String candidate = String.join(" ", tokens.subList(start, start + windowSize));
                    if (aliasTokens.size() == 1 && (candidate.contains(alias) || alias.contains(candidate))) continue;
                    double similarity = similarity(candidate, alias);
                    if (similarity >= 0.78) {
                        double ocrConfidence = line.words().subList(start, start + windowSize).stream()
                                .mapToDouble(word -> word.confidence())
                                .filter(value -> value >= 0)
                                .average().orElse(0) / 100.0;
                        double score = Math.min(0.89, similarity * 0.7 + ocrConfidence * 0.3);
                        best = better(best, new GenreMatch(genre, score, true));
                    }
                }
            }
        }
        return best;
    }

    private GenreMatch better(GenreMatch current, GenreMatch candidate) {
        return current == null || candidate.confidence() > current.confidence() ? candidate : current;
    }

    private double evidenceConfidence(List<String> aliasTokens, List<TextLine> lines) {
        return lines.stream().flatMap(line -> line.words().stream())
                .filter(word -> aliasTokens.contains(normalize(word.text())))
                .mapToDouble(word -> word.confidence())
                .filter(value -> value >= 0)
                .average().orElse(0) / 100.0;
    }

    private List<TextLine> lines(List<TesseractOcrService.OcrWord> words) {
        Map<String, List<TesseractOcrService.OcrWord>> grouped = words.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt((TesseractOcrService.OcrWord word) -> word.top())
                        .thenComparingInt(word -> word.left()))
                .collect(Collectors.groupingBy(word -> word.block() + ":" + word.line(), LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream().map(lineWords -> new TextLine(lineWords.stream()
                .map(word -> word.text()).collect(Collectors.joining(" ")), lineWords)).toList();
    }

    private double similarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        int max = Math.max(left.length(), right.length());
        if (max == 0) return 1.0;
        return 1.0 - ((double) distance(left, right) / max);
    }

    private int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length()];
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

    public record GenreMatch(String genre, double confidence, boolean uncertain) { }

    private record TextLine(String text, List<TesseractOcrService.OcrWord> words) { }
}
