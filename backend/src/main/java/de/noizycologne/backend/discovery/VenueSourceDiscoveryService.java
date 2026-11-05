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
    public VenueSourceDiscoveryService(EventPageFetcher fetcher, EventExtractor extractor) {
        this.fetcher = fetcher; this.extractor = extractor;
    }
    public record Found(URI url, EventExtractor.Extraction extraction) { }
    /** Breadth first, one domain, depth <= 2, at most 8 pages and 40 inspected links/page. */
    public List<Found> discover(String input, EventExtractor.Profile profile) throws IOException {
        URI root = SafeUrls.parse(input);
        record Step(URI url, int depth) { }
        var queue = new ArrayDeque<Step>(); queue.add(new Step(root, 0));
        Set<URI> seen = new HashSet<>(); Map<String, String> robots = new HashMap<>();
        List<Found> found = new ArrayList<>();
        while (!queue.isEmpty() && seen.size() < 8) {
            Step step = queue.remove(); if (!seen.add(step.url())) continue;
            try {
                if (!fetcher.robotsAllowed(step.url(), robots)) throw new EventPageFetcher.FetchFailure("ROBOTS_BLOCKED");
                var page = fetcher.fetch(step.url());
                var extraction = extractor.extract(page, profile);
                found.add(new Found(step.url(), extraction));
                if (step.depth() >= 2 || page.body().contains("BEGIN:VCALENDAR")) continue;
                for (var link : Jsoup.parse(page.body(), step.url().toString()).select("a[href], link[href]").stream().limit(40).toList()) {
                    String hint = (link.text() + " " + link.attr("href") + " " + link.attr("type")).toLowerCase(Locale.ROOT);
                    if (!hint.matches(".*(event|part(y|ies)|programm|kalender|veranstaltung|\\.ics|calendar).*") ) continue;
                    try {
                        String href = link.absUrl("href").split("#", 2)[0];
                        URI next = SafeUrls.parse(href);
                        if (next.getHost().equalsIgnoreCase(root.getHost()) && queue.size() < 16 && !seen.contains(next)) queue.add(new Step(next, step.depth() + 1));
                    } catch (IllegalArgumentException ignored) { }
                }
            } catch (IOException e) {
                if (step.depth() == 0) throw e;
                found.add(new Found(step.url(), new EventExtractor.Extraction(List.of(), e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED")));
            }
        }
        return found;
    }
}
