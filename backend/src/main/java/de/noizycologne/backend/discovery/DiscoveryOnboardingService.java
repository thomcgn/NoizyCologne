package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded previews are not calendar imports or approval to override editorial decisions. */
@Service
public class DiscoveryOnboardingService {
    public record Report(String previewToken,String websiteUrl,String suggestedName,String checkedAt,int eventCount,int partyCount,
                         int knownSeriesCount,boolean incomplete,List<VenueSourceDiscoveryService.@NonNull Found> sources,List<@NonNull String> problems) { }
    private record Preview(Report report,Instant expires) { }
    private final Map<String,Preview> previews=new LinkedHashMap<>();
    private final Semaphore lock=new Semaphore(1);
    private final VenueSourceDiscoveryService discovery;
    private final DiscoveryEvaluationService evaluation;
    private final DiscoveryStore store;
    private final VenueRepository venues;
    public DiscoveryOnboardingService(VenueSourceDiscoveryService discovery,DiscoveryEvaluationService evaluation,DiscoveryStore store,VenueRepository venues) {
        this.discovery=discovery;this.evaluation=evaluation;this.store=store;this.venues=venues;
    }
    public Report preview(String website) throws IOException {
        URI root=SafeUrls.parse(website);
        if (!lock.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Website-Prüfung läuft bereits");
        try {
            List<VenueSourceDiscoveryService.@NonNull Found> sources;
            try { sources=discovery.discover(root.toString(),EventExtractor.Profile.defaults()); }
            catch (IOException e) {
                sources=List.of(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),
                    e instanceof EventPageFetcher.FetchFailure failure ? failure.status : "SCAN_FAILED")));
            }
            var rules=evaluation.rules();
            Map<String,EventExtractor.@NonNull Event> unique=new LinkedHashMap<>();
            for (var source:sources) for (var event:source.extraction().events())
                unique.putIfAbsent(EventNormalizer.text(event.title())+"|"+event.starts(),event);
            int partyCount=0;Set<String> known=new HashSet<>();
            for (var event:unique.values()) {
                var recognized=PartySeriesRecognition.recognize(event.title(),rules.parties(),rules.aliases());
                recognized.parties().forEach(party -> known.add(party.id()));
                if (!PartyOnlyPolicy.concert(event) && (!recognized.parties().isEmpty() || DiscoveryGenreFilter.matches(event,rules.genres()))) partyCount++;
            }
            List<@NonNull String> problems=new ArrayList<>();
            for (var source:sources) {
                problems.addAll(source.diagnostics());
                if (!List.of("EVENTS_FOUND","SOURCE_LINKS_FOUND","NO_EVENTS","EXCLUDED_CONCERTS").contains(source.extraction().status()))
                    problems.add(Objects.requireNonNull(source.extraction().status()));
            }
            String name=sources.stream().filter(source -> source.url().equals(root)).map(VenueSourceDiscoveryService.Found::siteName)
                .filter(value -> !value.isBlank()).findFirst().orElse("");
            if (name.isBlank()) name=unique.values().stream().map(EventExtractor.Event::location).filter(value -> !value.isBlank()).findFirst().orElse(root.getHost());
            String token=UUID.randomUUID().toString();
            var report=new Report(token,root.toString(),name,Instant.now().toString(),unique.size(),partyCount,known.size(),
                sources.isEmpty() || sources.stream().anyMatch(source -> !source.extraction().complete()),List.copyOf(sources),problems.stream().distinct().toList());
            synchronized (previews) {
                previews.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
                if (previews.size()>=32) previews.remove(previews.keySet().iterator().next());
                previews.put(token,new Preview(report,Instant.now().plusSeconds(300)));
            }
            return report;
        } finally { lock.release(); }
    }
    @Transactional
    public void saveSources(String venueId,String previewToken) {
        Preview preview;
        synchronized (previews) { preview=previews.get(previewToken); }
        if (preview==null || preview.expires().isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Website-Prüfung abgelaufen; bitte erneut prüfen");
        var venue=venues.findById(venueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Location nicht gefunden"));
        if (!SafeUrls.parse(venue.getWebsiteUrl()).equals(SafeUrls.parse(preview.report().websiteUrl())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Website wurde geändert; bitte erneut prüfen");
        for (var found:preview.report().sources()) {
            if (found.extraction().events().isEmpty() && !found.extraction().status().equals("NO_EVENTS")) continue;
            boolean official=found.url().getHost().equalsIgnoreCase(SafeUrls.parse(venue.getWebsiteUrl()).getHost());
            String id=store.source(venueId,found.url().toString(),official ? "OFFICIAL" : "EXTERNAL",official);
            store.fetched(id,found);
        }
    }
}
