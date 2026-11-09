package de.noizycologne.backend.media;

import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.genre.Genre;
import de.noizycologne.backend.genre.GenreAlias;
import de.noizycologne.backend.genre.GenreAliasRepository;
import de.noizycologne.backend.genre.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;

import de.noizycologne.backend.genre.GenreNormalizer;
import de.noizycologne.backend.genre.GenreImportCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class GenreLexicon {
    @Nullable
    private final GenreRepository genreRepository;
    @Nullable
    private final GenreAliasRepository aliasRepository;
    private volatile List<@NonNull Entry> entries;

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
        List<@NonNull Genre> genres = genreSource.findAllByActiveTrueOrderByCanonicalNameAsc();
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
        List<@NonNull TextLine> lines = lines(words);
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

    private GenreMatch bestMatch(String genre, List<String> aliases, String text, List<@NonNull TextLine> lines) {
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

    private double evidenceConfidence(List<String> aliasTokens, List<@NonNull TextLine> lines) {
        return lines.stream().flatMap(line -> line.words().stream())
                .filter(word -> aliasTokens.contains(normalize(word.text())))
                .mapToDouble(word -> word.confidence())
                .filter(value -> value >= 0)
                .average().orElse(0) / 100.0;
    }

    private List<@NonNull TextLine> lines(List<TesseractOcrService.OcrWord> words) {
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

    String normalize(String value) { return GenreNormalizer.normalize(value); }

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

    private List<@NonNull Entry> defaultEntries() {
        return GenreImportCatalog.base().stream().map(row -> {
            List<String> values = new java.util.ArrayList<>();
            values.add(row.canonicalName());
            values.addAll(row.aliases());
            return new Entry(row.canonicalName(), List.copyOf(values));
        }).toList();
    }

    public record GenreMatch(String genre, double confidence, boolean uncertain) { }

    private record TextLine(String text, List<TesseractOcrService.OcrWord> words) { }
    private record Entry(String canonicalName, List<String> aliases) { }
}
