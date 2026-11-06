package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import java.util.*;

/** Denominator is all active project genres; aliases identify a genre, never increase its weight. */
public final class GenreCoverage {
    public record Genre(String id,String displayName,Set<@NonNull String> terms) { }
    public record Result(int matched,int total,int percent,List<@NonNull String> names,boolean eligible) { }
    private GenreCoverage() { }
    public static Result assess(EventExtractor.Event event,List<@NonNull Genre> genres) {
        String evidence=event.evidence().replaceAll("https?://[^\\s\"<>]+", " ");
        var facts=new EventExtractor.Event(event.identity(),event.title(),event.url(),event.starts(),evidence,event.state(),event.location());
        var matches=genres.stream().filter(g -> {
            Set<String> terms=new LinkedHashSet<>();for (String term:g.terms()) terms.add(term);
            return DiscoveryGenreFilter.matches(facts,terms);
        }).toList();
        int total=genres.size(),matched=matches.size();
        List<@NonNull String> names=new ArrayList<>();
        for (var genre:matches) if (!names.contains(genre.displayName())) names.add(Objects.requireNonNull(genre.displayName()));
        return new Result(matched,total,total==0 ? 0 : matched*100/total,List.copyOf(names),total>0 && matched*2>=total);
    }
}
