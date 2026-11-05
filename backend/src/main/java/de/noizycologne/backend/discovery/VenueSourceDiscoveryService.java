package de.noizycologne.backend.discovery;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;
import java.io.IOException;

@Service
public class VenueSourceDiscoveryService {
    private final EventPageFetcher fetcher;
    private final EventExtractor extractor;
    private final DiscoveryGenreFilter genres;
    private final de.noizycologne.backend.party.PartySeriesRepository parties;
    private final DiscoveryStore store;
    public VenueSourceDiscoveryService(EventPageFetcher fetcher, EventExtractor extractor, DiscoveryGenreFilter genres) {
        this(fetcher,extractor,genres,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public VenueSourceDiscoveryService(EventPageFetcher fetcher,EventExtractor extractor,DiscoveryGenreFilter genres,
            de.noizycologne.backend.party.PartySeriesRepository parties,DiscoveryStore store) {
        this.fetcher=fetcher;this.extractor=extractor;this.genres=genres;this.parties=parties;this.store=store;
    }
    public record Found(URI url, EventExtractor.Extraction extraction) { }
    /** Calendar navigation before details, one host, depth <= 2, at most 8 pages and 200 inspected links/page. */
    public List<Found> discover(String input, EventExtractor.Profile profile) throws IOException {
        URI root = SafeUrls.parse(input);
        record Step(URI url, int depth, boolean detail) { }
        var queue = new ArrayDeque<Step>(); queue.add(new Step(root, 0, false));
        Set<URI> seen = new HashSet<>(); Map<String, String> robots = new HashMap<>();
        List<Found> found = new ArrayList<>();
        Set<String> genreTerms = genres.terms();
        List<String> knownNames=new ArrayList<>();
        if (parties!=null) parties.findAll().forEach(p -> knownNames.add(p.getName()));
        if (store!=null) store.aliases().values().forEach(knownNames::addAll);
        Set<URI> eligibleDetails = new HashSet<>();
        while (!queue.isEmpty() && seen.size() < 8) {
            // Find programme sections before spending the page budget on event details.
            Step step = queue.stream().filter(s -> !s.detail()).findFirst().orElse(queue.getFirst());
            queue.remove(step);
            if (!seen.add(step.url())) continue;
            try {
                if (!fetcher.robotsAllowed(step.url(), robots)) throw new EventPageFetcher.FetchFailure("ROBOTS_BLOCKED");
                var page = fetcher.fetch(step.url());
                var extraction = extractor.extract(page, profile);
                found.add(new Found(step.url(), extraction));
                if (step.depth() >= 2) continue;
                if (step.detail()) continue;
                Set<URI> eventLinks = new HashSet<>();
                for (var event : extraction.events()) {
                    try {
                        URI detail = SafeUrls.parse(step.url().resolve(event.url()).toString().split("#", 2)[0]);
                        eventLinks.add(detail);
                        boolean known=knownNames.stream().anyMatch(name -> EventMatcher.nameMatches(event.title(),name));
                        boolean partyLead=EventNormalizer.text(event.title()).matches(".*\\b([\\p{L}]*party|partys|parties|clubnacht|disco|dancefloor|dj|djs)\\b.*");
                        if (PartyOnlyPolicy.concert(event) || (!known && (EventMatcher.unrelated(event) || (!partyLead && !DiscoveryGenreFilter.matches(event, genreTerms)))) || detail.equals(step.url())
                                || !detail.getHost().equalsIgnoreCase(root.getHost())) continue;
                        eligibleDetails.add(detail);
                        if (!seen.contains(detail) && queue.size() < 16)
                            queue.addFirst(new Step(detail, step.depth() + 1, true));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (page.body().contains("BEGIN:VCALENDAR")) continue;
                boolean programmeLink = false;
                int navigationLinks = 0;
                for (var link : Jsoup.parse(page.body(), step.url().toString()).select("a[href], link[href][type=text/calendar]").stream().limit(200).toList()) {
                    try {
                        String href = link.absUrl("href").split("#", 2)[0];
                        URI next = SafeUrls.parse(href);
                        String label = EventNormalizer.text(link.text().isBlank()
                                ? (link.hasAttr("aria-label") ? link.attr("aria-label") : link.attr("title")) : link.text());
                        boolean overview = label.matches("events|party|partys|parties|programm|kalender|veranstaltungen|calendar|veranstaltungskalender|alle events|zum programm|mehr events|live|live shows|konzerte|shows|upcoming events|whats on")
                                || next.getPath().matches("(?i).*/(events|party|partys|parties|programm|kalender|veranstaltungen|calendar|live-shows|konzerte|shows)/?")
                                || next.getPath().toLowerCase(Locale.ROOT).endsWith(".ics")
                                || link.attr("type").equalsIgnoreCase("text/calendar");
                        if (!overview || PartyOnlyPolicy.concertLink(label,next)) continue;
                        if (!eventLinks.contains(next) && !next.equals(step.url()) && next.getHost().equalsIgnoreCase(root.getHost())) {
                            programmeLink = true;
                            if (!seen.contains(next) && queue.size() < 16 && navigationLinks++ < 40
                                    && queue.stream().noneMatch(s -> s.url().equals(next)))
                                queue.add(new Step(next, step.depth() + 1, false));
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
                if (programmeLink && extraction.events().isEmpty() && extraction.status().equals("UNINTERPRETABLE"))
                    found.set(found.size() - 1, new Found(step.url(), new EventExtractor.Extraction(List.of(), "SOURCE_LINKS_FOUND")));
            } catch (IOException e) {
                if (step.depth() == 0) throw e;
                found.add(new Found(step.url(), new EventExtractor.Extraction(List.of(), e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED")));
            }
        }
        return enrich(found, eligibleDetails);
    }
    /** Preserve overview evidence, including date conflicts and cancellation, when adding detail data. */
    private List<Found> enrich(List<Found> found, Set<URI> eligible) {
        Map<URI, Found> pages = new HashMap<>();
        found.forEach(f -> pages.put(f.url(), f));
        Map<URI, EventExtractor.Event> mergedDetails = new HashMap<>();
        List<Found> result = new ArrayList<>();
        for (Found page : found) {
            List<EventExtractor.Event> events = new ArrayList<>();
            for (var event : page.extraction().events()) {
                URI url;
                try { url = SafeUrls.parse(page.url().resolve(event.url()).toString().split("#", 2)[0]); }
                catch (IllegalArgumentException e) { events.add(event); continue; }
                if (!eligible.contains(url) || url.equals(page.url())) { events.add(event); continue; }
                Found detail = pages.get(url);
                var matching = detail == null ? List.<EventExtractor.Event>of() : detail.extraction().events().stream()
                        .filter(e -> EventNormalizer.text(e.title()).equals(EventNormalizer.text(event.title()))).toList();
                if (matching.size() != 1) {
                    events.add(new EventExtractor.Event(event.identity(), event.title(), event.url(), event.starts(),
                            event.evidence(), event.state() + " UNCONFIRMED Detailseite nicht eindeutig auswertbar", event.location()));
                    continue;
                }
                var d = matching.get(0);
                List<String> starts = new ArrayList<>(event.starts()); starts.addAll(d.starts());
                String evidence = "Übersicht: " + event.evidence() + "\nDetailseite " + url + ": " + d.evidence();
                String state = event.state() + " " + d.state();
                if (!event.location().isBlank() && !d.location().isBlank()
                        && !EventNormalizer.text(event.location()).equals(EventNormalizer.text(d.location()))) state += " UNCONFIRMED Location-Widerspruch";
                var merged = new EventExtractor.Event(event.identity().isBlank() ? url.toString() : event.identity(),
                        event.title(), url.toString(), starts.stream().distinct().toList(),
                        evidence.substring(0, Math.min(evidence.length(), 8000)), state,
                        d.location().isBlank() ? event.location() : d.location());
                events.add(merged); mergedDetails.put(url, merged);
            }
            result.add(new Found(page.url(), new EventExtractor.Extraction(events, page.extraction().status())));
        }
        // Both source records use the same merged identity/evidence, preventing duplicate proposals.
        return result.stream().map(f -> {
            if (mergedDetails.containsKey(f.url()))
                return new Found(f.url(), new EventExtractor.Extraction(List.of(mergedDetails.get(f.url())), "EVENTS_FOUND"));
            if (eligible.contains(f.url()))
                return new Found(f.url(), new EventExtractor.Extraction(f.extraction().events().stream().map(e ->
                        new EventExtractor.Event(e.identity(), e.title(), e.url(), e.starts(), e.evidence(),
                                e.state() + " UNCONFIRMED Detailzuordnung unklar", e.location())).toList(), f.extraction().status()));
            return f;
        }).toList();
    }
}
