package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Compare evidenced event genres with the active portfolio; standards are a separate fallback. */
public final class GenreCoverage {
    public record Genre(String id,String displayName,Set<@NonNull String> terms,boolean active) {
        public Genre(String id,String displayName,Set<@NonNull String> terms) { this(id,displayName,terms,true); }
    }
    public record Result(int matched,int total,int percent,List<@NonNull String> names,boolean eligible,
                         List<@NonNull String> sourceNames,List<@NonNull String> standardNames,String basis,List<@NonNull String> suggestedStandardGenres) { }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Pattern LABEL=Pattern.compile("(?im)(?:^|[\\n;])\\s*(?:genres?|musikrichtung|styles?)\\s*:\\s*([^\\n.;]+)");
    private GenreCoverage() { }
    private record Hit(Genre genre,int start,int end) { }
    public static Result assess(EventExtractor.Event event,List<@NonNull Genre> genres) { return assess(event,genres,List.of()); }
    public static Result assess(EventExtractor.Event event,List<@NonNull Genre> genres,Collection<String> standards) {
        String evidence=event.evidence().replaceAll("https?://[^\\s\"<>]+", " ");
        var facts=new EventExtractor.Event(event.identity(),event.title(),event.url(),event.starts(),evidence,event.state(),event.location(),event.place());
        Map<String,Genre> source=new LinkedHashMap<>();
        // Keep compound names as one genre; a source saying Hard Rock does not also say Rock.
        String normalized=EventNormalizer.text(facts.title()+" "+facts.evidence());
        List<@NonNull Hit> hits=new ArrayList<>(),accepted=new ArrayList<>();
        if (!PartyOnlyPolicy.concert(event)) for (var genre:genres) for (String term:genre.terms()) {
            String name=EventNormalizer.text(term);if (name.isBlank()) continue;
            var pattern=Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(name)+"(?:party|partys|parties)?(?![\\p{L}\\p{N}])").matcher(normalized);
            while (pattern.find()) hits.add(new Hit(genre,pattern.start(),pattern.end()));
        }
        hits.sort(Comparator.<@NonNull Hit>comparingInt(hit -> hit.end()-hit.start()).reversed().thenComparingInt(Hit::start));
        for (var hit:hits) if (accepted.stream().noneMatch(other -> hit.start()<other.end() && hit.end()>other.start())) {
            accepted.add(hit);source.put(hit.genre().id(),hit.genre());
        }
        for (String declared:declaredGenres(event.evidence())) addDeclared(source,declared,genres);
        Map<String,Genre> defaults=new LinkedHashMap<>();
        for (String standard:standards) if (!standard.isBlank()) addDeclared(defaults,standard,genres);
        var basis=defaults.isEmpty() ? source : defaults;
        List<@NonNull String> names=new ArrayList<>(),sourceNames=new ArrayList<>(),standardNames=new ArrayList<>();
        for (var genre:source.values()) sourceNames.add(Objects.requireNonNull(genre.displayName()));
        for (var genre:defaults.values()) standardNames.add(Objects.requireNonNull(genre.displayName()));
        for (var genre:basis.values()) if (genre.active()) names.add(Objects.requireNonNull(genre.displayName()));
        int total=basis.size(),matched=names.size();
        return new Result(matched,total,total==0 ? 0 : matched*100/total,List.copyOf(names),total>0 && matched*2>=total,
            List.copyOf(sourceNames),List.copyOf(standardNames),!defaults.isEmpty() ? "STANDARD" : !source.isEmpty() ? "SOURCE" : "MISSING",suggestedStandards(event,genres));
    }
    /** Only explicit source declarations suggest defaults; title/text matches remain coverage evidence. */
    private static List<@NonNull String> suggestedStandards(EventExtractor.Event event,List<@NonNull Genre> genres) {
        Map<String,Genre> declared=new LinkedHashMap<>();
        for (String list:declaredGenres(event.evidence())) for (String value:list.split("[,/|;+]|\\s+&\\s+")) {
            if (value.matches("(?is).*(?:[?]|\\b(?:vermutlich|vielleicht|eventuell|evtl|möglich|unbestätigt|ungeprüft|possibly|unconfirmed|maybe|tbc)\\b).*")) continue;
            addDeclared(declared,value,genres);
        }
        List<@NonNull String> names=new ArrayList<>();
        for (var genre:declared.values()) if (genre.active()) names.add(Objects.requireNonNull(genre.displayName()));
        return List.copyOf(names);
    }
    private static void addDeclared(Map<String,Genre> found,String value,List<@NonNull Genre> genres) {
        String text=value.trim();String normalized=EventNormalizer.text(text);
        if (normalized.isBlank()) return;
        var known=genres.stream().filter(g -> g.terms().stream().anyMatch(term -> EventNormalizer.text(term).equals(normalized))).toList();
        if (!known.isEmpty()) {var genre=Objects.requireNonNull(known.getFirst());found.put(genre.id(),genre);return;}
        String[] parts=text.split("[,/|;+]|\\s+&\\s+");
        if (parts.length>1) {for (String part:parts) addDeclared(found,part,genres);return;}
        if (text.length()>100 || text.contains("http")) return;
        found.put("source:"+normalized,new Genre("source:"+normalized,text,Set.of(text),false));
    }
    static List<@NonNull String> declaredGenres(String evidence) {
        List<@NonNull String> values=new ArrayList<>();String text=evidence;
        if (evidence.stripLeading().startsWith("{")) try {
            JsonNode node=JSON.readTree(evidence);var genre=node.path("genre");
            if (genre.isString()) values.add(Objects.requireNonNull(genre.asString()));
            else if (genre.isArray()) for (var value:genre) if (value.isString()) values.add(Objects.requireNonNull(value.asString()));
            text=node.path("description").asString("");
        } catch (RuntimeException ignored) { /* Truncated legacy evidence remains usable as plain text. */ }
        var labels=LABEL.matcher(text);
        while (labels.find() && values.size()<50) values.add(Objects.requireNonNull(labels.group(1)));
        return List.copyOf(values);
    }
}
