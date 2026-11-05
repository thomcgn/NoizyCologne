package de.noizycologne.backend.media;

import de.noizycologne.backend.genre.Genre;
import de.noizycologne.backend.genre.GenreAlias;
import de.noizycologne.backend.genre.GenreAliasRepository;
import de.noizycologne.backend.genre.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class GenreLexicon {
    @Nullable
    private final GenreRepository genreRepository;
    @Nullable
    private final GenreAliasRepository aliasRepository;
    private volatile List<Entry> entries;

    public GenreLexicon() {
        this.genreRepository = null;
        this.aliasRepository = null;
        this.entries = defaultEntries();
    }

    @Autowired
    public GenreLexicon(GenreRepository genreRepository, GenreAliasRepository aliasRepository) {
        this.genreRepository = genreRepository;
        this.aliasRepository = aliasRepository;
        this.entries = List.of();
        loadEntries();
    }

    public synchronized void refresh() {
        loadEntries();
    }

    // Constructors must not dispatch an overridable method on a partly initialized subclass.
    private void loadEntries() {
        var genreSource = genreRepository;
        var aliasSource = aliasRepository;
        if (genreSource == null || aliasSource == null) return;
        List<Genre> genres = genreSource.findAllByActiveTrueOrderByCanonicalNameAsc();
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (GenreAlias alias : aliasSource.findAllByGenreIdIn(genres.stream().map(genre -> genre.getId()).toList())) {
            aliases.computeIfAbsent(alias.getGenreId(), ignored -> new java.util.ArrayList<>()).add(alias.getValue());
        }
        entries = genres.stream().map(genre -> {
            List<String> values = new java.util.ArrayList<>();
            values.add(genre.getCanonicalName());
            values.addAll(aliases.getOrDefault(genre.getId(), List.of()));
            return new Entry(genre.getCanonicalName(), values.stream().distinct().toList());
        }).toList();
    }

    public List<String> findGenres(List<TesseractOcrService.OcrWord> words) {
        return findGenreMatches(words).stream().map(match -> match.genre()).toList();
    }

    public List<GenreMatch> findGenreMatches(List<TesseractOcrService.OcrWord> words) {
        List<TextLine> lines = lines(words);
        String text = normalize(lines.stream().map(line -> line.text()).collect(Collectors.joining(" ")));
        Map<String, GenreMatch> found = new LinkedHashMap<>();
        entries.stream()
                .sorted((left, right) -> Integer.compare(longestAlias(right.aliases()), longestAlias(left.aliases())))
                .forEach(entry -> {
                    GenreMatch match = bestMatch(entry.canonicalName(), entry.aliases(), text, lines);
                    if (match != null) found.put(entry.canonicalName(), match);
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
                if ("rock".equals(alias) && lines.stream().anyMatch(line -> isPromotionalRockLine(line))
                        && lines.stream().noneMatch(line -> isGenreListLine(line, alias))) continue;
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
                .filter(word -> word != null)
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

    private boolean isPromotionalRockLine(TextLine line) {
        String value = normalize(line.text());
        return value.matches(".*\\b(let's|lets|let us) rock\\b.*") || value.matches(".*\\brock on\\b.*");
    }

    private boolean isGenreListLine(TextLine line, String alias) {
        String value = normalize(line.text());
        return value.contains(" from ") || value.startsWith("from ") || value.contains(" to ")
                || value.contains("/") || value.contains(",") || value.contains(" genre ")
                || value.contains(" genres ");
    }

    private List<Entry> defaultEntries() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        add(values, "Rock", "rock"); add(values, "Alternative", "alternative"); add(values, "Indie", "indie"); add(values, "Punk", "punk");
        add(values, "Punkrock", "punkrock", "punk rock"); add(values, "Garage Rock", "garage rock", "garage-rock");
        add(values, "Garage-Punk", "garage punk", "garage-punk", "garagepunk");
        add(values, "Rock’n’Roll", "rock'n'roll", "rock n roll", "rock'n roll", "rock and roll", "rnr", "r&r");
        add(values, "50s Rock’n’Roll", "50s rnr", "50's rnr", "50s rock n roll");
        add(values, "Wild R&B", "wild rnb", "wild r&b", "wild rhythm and blues");
        add(values, "Rockabilly", "rockabilly"); add(values, "Psychobilly", "psychobilly"); add(values, "Emo", "emo"); add(values, "Hardcore", "hardcore");
        add(values, "Metal", "metal"); add(values, "Metalcore", "metalcore", "metal core"); add(values, "Electronicore", "electronicore"); add(values, "Heavy Metal", "heavy metal");
        add(values, "Thrash Metal", "thrash metal", "thrash-metal"); add(values, "Death Metal", "death metal", "death-metal");
        add(values, "Black Metal", "black metal", "black-metal"); add(values, "Doom", "doom"); add(values, "Hard Rock", "hard rock", "hard-rock");
        add(values, "Psych", "psych", "psychedelic"); add(values, "Ska", "ska"); add(values, "Soul", "soul"); add(values, "Northern Soul", "northern soul");
        add(values, "Popcorn", "popcorn"); add(values, "Rhythm & Blues", "rhythm and blues", "rhythm & blues", "rnb");
        add(values, "Post-Punk", "post-punk", "post punk"); add(values, "New Wave", "new wave"); add(values, "Darkwave", "darkwave", "dark wave");
        add(values, "Gothic Rock", "gothic rock"); add(values, "Britpop", "britpop");
        add(values, "Singer-Songwriter", "singer-songwriter", "singer songwriter"); add(values, "Folk", "folk"); add(values, "Pop", "pop");
        add(values, "Sonstiges", "sonstiges", "other");
        return values.entrySet().stream().map(item -> new Entry(item.getKey(), item.getValue())).toList();
    }

    private void add(Map<String, List<String>> values, String canonical, String... aliases) {
        values.put(canonical, List.of(aliases));
    }

    public record GenreMatch(String genre, double confidence, boolean uncertain) { }

    private record TextLine(String text, List<TesseractOcrService.OcrWord> words) { }
    private record Entry(String canonicalName, List<String> aliases) { }
}
