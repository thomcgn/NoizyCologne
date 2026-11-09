package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;
import java.io.IOException;

@Service
public class VenueSourceDiscoveryService {
    private final EventPageFetcher fetcher;
    private final BrowserPageRenderer browser;
    private final EventExtractor extractor;
    private final DiscoveryGenreFilter genres;
    private final de.noizycologne.backend.party.PartySeriesRepository parties;
    private final DiscoveryStore store;
    public VenueSourceDiscoveryService(EventPageFetcher fetcher, EventExtractor extractor, DiscoveryGenreFilter genres) {
        this(fetcher,extractor,genres,null,null,null);
    }
    public VenueSourceDiscoveryService(EventPageFetcher fetcher,EventExtractor extractor,DiscoveryGenreFilter genres,
            de.noizycologne.backend.party.PartySeriesRepository parties,DiscoveryStore store) {
        this(fetcher,extractor,genres,parties,store,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public VenueSourceDiscoveryService(EventPageFetcher fetcher,EventExtractor extractor,DiscoveryGenreFilter genres,
            de.noizycologne.backend.party.PartySeriesRepository parties,DiscoveryStore store,BrowserPageRenderer browser) {
        this.browser=browser;this.fetcher=fetcher;this.extractor=extractor;this.genres=genres;this.parties=parties;this.store=store;
    }
    public record Found(URI url, EventExtractor.Extraction extraction,String fetchMethod,List<@NonNull String> diagnostics,String siteName) {
        public Found(URI url,EventExtractor.Extraction extraction) { this(url,extraction,"HTTP",List.of(),""); }
        public Found(URI url,EventExtractor.Extraction extraction,String method,List<@NonNull String> diagnostics) { this(url,extraction,method,diagnostics,""); }
        @NonNull Found withExtraction(EventExtractor.Extraction updated) { return new Found(url,updated,fetchMethod,diagnostics,siteName); }
    }
    /** Calendar navigation before details, one host, depth <= 2, at most 8 pages and 200 inspected links/page. */
    public List<@NonNull Found> discover(String input, EventExtractor.Profile profile) throws IOException {
        return discover(input,profile,false);
    }
    /** Independent portals may expose labelled event links before any dates can be extracted. */
    public List<@NonNull Found> discoverSpecial(String input,EventExtractor.Profile profile) throws IOException {
        return discover(input,profile,true);
    }
    private List<@NonNull Found> discover(String input,EventExtractor.Profile profile,boolean independent) throws IOException {
        URI root = SafeUrls.parse(independent?CanonicalSourceUrl.of(input):input);
        record Step(URI url, int depth, boolean detail,boolean browserPass) {
            Step(URI url,int depth,boolean detail) {this(url,depth,detail,false);}
        }
        var queue = new ArrayDeque<Step>(); queue.add(new Step(root, 0, false));
        Set<URI> seen = new HashSet<>(); Map<String, String> robots = new HashMap<>();
        List<@NonNull Found> found = new ArrayList<>();
        Set<String> genreTerms = genres.terms();
        List<String> knownNames=new ArrayList<>();
        if (parties!=null) parties.findAll().forEach(p -> knownNames.add(p.getName()));
        if (store!=null) store.aliases().values().forEach(knownNames::addAll);
        Set<URI> eligibleDetails = new HashSet<>();
        Map<URI,Step> deferred=new LinkedHashMap<>();
        Map<URI,EventPageFetcher.Page> staticPages=new HashMap<>();
        int browserJobs=0;
        long deadline=System.nanoTime()+60_000_000_000L;
        while (!queue.isEmpty() || !deferred.isEmpty()) {
            if(seen.size()>=8 && !queue.isEmpty()) {
                var previous=Objects.requireNonNull(found.removeLast());
                List<@NonNull String> limit=new ArrayList<>(previous.diagnostics());limit.add("FURTHER_PAGES_UNPROCESSED: page limit 8");
                found.add(new Found(previous.url(),new EventExtractor.Extraction(previous.extraction().events(),previous.extraction().status(),false),previous.fetchMethod(),limit,previous.siteName()));queue.clear();
            }
            if(queue.isEmpty() && (deferred.isEmpty() || found.stream().anyMatch(f -> f.extraction().events().stream()
                    .anyMatch(event -> event.starts().stream().anyMatch(start -> EventNormalizer.date(start)!=null))) && deferred.values().stream().noneMatch(s -> EventExtractor.interactiveList(Jsoup.parse(Objects.requireNonNull(staticPages.get(s.url())).body()))))) break;
            if (System.nanoTime()>deadline) {
                found.add(new Found(root,new EventExtractor.Extraction(List.of(),"CRAWL_LIMIT"),"HTTP",List.of("CRAWL_LIMIT")));
                break;
            }
            // Find programme sections before spending the page budget on event details.
            Step step;
            if(queue.isEmpty()) {var next=deferred.entrySet().iterator().next();step=next.getValue();deferred.remove(next.getKey());}
            else {step=queue.stream().filter(s -> !s.detail()).findFirst().orElse(queue.getFirst());queue.remove(step);}
            if (!step.browserPass() && !seen.add(step.url())) continue;
            try {
                if (!step.browserPass() && !fetcher.robotsAllowed(step.url(), robots)) throw new EventPageFetcher.FetchFailure("ROBOTS_BLOCKED","robots.txt untersagt den Abruf von " + step.url());
                var page = step.browserPass() ? Objects.requireNonNull(staticPages.get(step.url())) : fetcher.fetch(step.url());
                staticPages.putIfAbsent(step.url(),page);
                if(step.browserPass()) found.removeIf(previous -> previous.url().equals(step.url()) && previous.fetchMethod().equals("HTTP"));
                List<EventExtractor.@NonNull Event> embeddedEvents=new ArrayList<>();
                var extraction = step.detail()?extractor.extractDetail(page,profile):extractor.extract(page, profile);
                var portalLinks=independent && !step.detail() && step.depth()<2 ? portalLinks(page,root) : List.<URI>of();
                if (!portalLinks.isEmpty() && extraction.events().isEmpty())
                    extraction=new EventExtractor.Extraction(List.of(),"SOURCE_LINKS_FOUND");
                for (var detail:portalLinks) if (!seen.contains(detail) && queue.size()<16 && queue.stream().noneMatch(s -> s.url().equals(detail)))
                    queue.add(new Step(detail,step.depth()+1,true));
                String method="HTTP";List<@NonNull String> diagnostics=new ArrayList<>();
                if(extraction.events().size()>=200) diagnostics.add("EVENT_LIMIT: 200");
                boolean needsBrowser=browser!=null && portalLinks.isEmpty() && extractor.needsRendering(page,extraction);
                boolean deferBrowser=needsBrowser && !step.browserPass() && hasStaticProgrammeLink(page);
                if(deferBrowser) {staticPages.put(step.url(),page);deferred.put(step.url(),new Step(step.url(),step.depth(),step.detail(),true));}
                if (needsBrowser && !deferBrowser) {
                    var rendered=browserJobs++<2 ? browser.render(step.url())
                        : new BrowserPageRenderer.Result("BROWSER_LIMIT",List.of(),List.of("BROWSER_LIMIT"),false);
                    diagnostics.addAll(rendered.warnings());
                    if (!rendered.status().equals("BROWSER_DISABLED")) method="BROWSER";
                    if (!rendered.pages().isEmpty()) {
                        method="BROWSER";
                        StringBuilder combined=new StringBuilder(page.body()).append("\n");
                        for (var renderedPage:rendered.pages()) {
                            if (renderedPage.url().equals(step.url())) combined.append(renderedPage.body()).append("\n");
                            else {
                                var embedded=extractor.extract(renderedPage,profile);
                                if(renderedPage.url().getHost().equalsIgnoreCase(root.getHost())) embeddedEvents.addAll(embedded.events());
                                found.add(new Found(renderedPage.url(),new EventExtractor.Extraction(embedded.events(),embedded.status(),false),"BROWSER",diagnostics));
                            }
                        }
                        if (!combined.isEmpty()) {
                            page=new EventPageFetcher.Page(step.url(),Objects.requireNonNull(combined.toString()),"text/html");
                            var parsed=step.detail()?extractor.extractDetail(page,profile):extractor.extract(page,profile);
                            extraction=new EventExtractor.Extraction(parsed.events(),parsed.status(),false);
                        }
                        if (!rendered.status().equals("RENDERED"))
                            extraction=new EventExtractor.Extraction(extraction.events(),rendered.status(),false);
                    } else if (!rendered.status().equals("BROWSER_DISABLED"))
                        extraction=new EventExtractor.Extraction(extraction.events(),rendered.status(),false);
                }
                var metadata=Jsoup.parse(page.body());
                var site=metadata.selectFirst("meta[property=og:site_name][content]");
                String siteName=site==null ? metadata.title() : site.attr("content");
                found.add(new Found(page.url(), extraction,method,diagnostics,siteName.substring(0,Math.min(200,siteName.length()))));
                if (step.depth() >= 2) {
                    if(metadata.selectFirst("a[rel=next]")!=null) diagnostics.add("FURTHER_PAGES_UNPROCESSED: depth limit 2");
                    continue;
                }
                if (step.detail()) continue;
                Set<URI> eventLinks = new HashSet<>();
                for (var event : java.util.stream.Stream.concat(extraction.events().stream(),embeddedEvents.stream()).sorted(Comparator.comparingInt(event -> knownNames.stream().anyMatch(name -> PartySeriesRecognition.containsName(event.title(),name)) ? 0 : DiscoveryGenreFilter.matches(event,genreTerms) ? 1 : 2)).toList()) {
                    try {
                        URI detail = SafeUrls.parse(independent?CanonicalSourceUrl.of(step.url().resolve(event.url()).toString().split("#",2)[0]):step.url().resolve(event.url()).toString().split("#",2)[0]);
                        eventLinks.add(detail);
                        // The overview may omit all genres, or hide the party/aftershow description.
                        // Only scoped, same-host event links are eligible; no editorial filter runs here.
                        if (detail.equals(step.url()) || !detail.getHost().equalsIgnoreCase(root.getHost())) continue;
                        eligibleDetails.add(detail);
                        if (!seen.contains(detail) && queue.size() < 16 && queue.stream().noneMatch(s -> s.url().equals(detail)))
                            queue.addLast(new Step(detail, step.depth() + 1, true));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (page.body().contains("BEGIN:VCALENDAR")) continue;
                boolean programmeLink = false;
                int navigationLinks = 0;
                for (var link : Jsoup.parse(page.body(), Objects.requireNonNull(page.url().toString())).select("a[href], link[href][type=text/calendar]").stream().limit(200).toList()) {
                    try {
                        String href = link.absUrl("href").split("#", 2)[0];
                        URI next = SafeUrls.parse(independent?CanonicalSourceUrl.of(href):href);
                        String label = EventNormalizer.text(link.text().isBlank()
                                ? (link.hasAttr("aria-label") ? link.attr("aria-label") : link.attr("title")) : link.text());
                        boolean pagination = link.attr("rel").equalsIgnoreCase("next") || label.matches("next(?: page)?|nächste[rns]? seite|weiter|mehr laden");
                        boolean overview = pagination || label.matches("events|party|partys|parties|programm|kalender|veranstaltungen|calendar|veranstaltungskalender|alle events|zum programm|mehr events|live|live shows|konzerte|shows|upcoming events|whats on")
                                || next.getPath().matches("(?i).*/(events|party|partys|parties|programm|kalender|veranstaltungen|calendar|live-shows|konzerte|shows)/?")
                                || next.getPath().toLowerCase(Locale.ROOT).endsWith(".ics")
                                || link.attr("type").equalsIgnoreCase("text/calendar");
                        if (!overview || !pagination && PartyOnlyPolicy.concertLink(label,next)) continue;
                        if (!eventLinks.contains(next) && !next.equals(step.url()) && next.getHost().equalsIgnoreCase(root.getHost())) {
                            programmeLink = true;
                            if (!seen.contains(next) && queue.size() < 16 && navigationLinks++ < 40
                                    && queue.stream().noneMatch(s -> s.url().equals(next)))
                                queue.add(new Step(next, step.depth() + 1, false));
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
                if (programmeLink && extraction.events().isEmpty() && extraction.status().equals("UNINTERPRETABLE"))
                    found.set(found.size() - 1, Objects.requireNonNull(found.getLast(),"Quellenbefund fehlt").withExtraction(new EventExtractor.Extraction(List.of(), "SOURCE_LINKS_FOUND")));
            } catch (IOException e) {
                found.add(new Found(step.url(), new EventExtractor.Extraction(List.of(), e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED"),
                    "HTTP",List.of(Objects.requireNonNull(Objects.toString(e.getMessage(),"HTTP-Abruf fehlgeschlagen: " + step.url())))));
            }
        }
        return enrich(found, eligibleDetails).stream().map(source -> {
            var parsed=Jsoup.parse(staticPages.containsKey(source.url())?Objects.requireNonNull(staticPages.get(source.url())).body():"");
            List<@NonNull String> details=new ArrayList<>();
            long dated=source.extraction().events().stream().filter(event -> EventVerification.assess(event,"").sufficient()).count();
            details.add("EVENT_CARDS_RECOGNIZED: "+source.extraction().events().size());
            details.add("RELIABLE_DATES: "+dated);
            details.add("INCOMPLETE_ENTRIES: "+source.extraction().events().stream().filter(event ->
                !EventVerification.assess(event,"").sufficient() || event.state().contains("Detailseite nicht eindeutig") || event.state().contains("Detailzuordnung unklar")).count());
            long read=source.extraction().events().stream().map(event -> {
                try{return source.url().resolve(event.url());}catch(IllegalArgumentException ignored){return source.url();}
            }).filter(url -> !url.equals(source.url()) && eligibleDetails.contains(url) && found.stream().anyMatch(other -> other.url().equals(url) && !other.extraction().events().isEmpty())).distinct().count();
            details.add("DETAIL_PAGES_READ: "+(eligibleDetails.contains(source.url()) && !source.extraction().events().isEmpty()?1:read));
            if(eligibleDetails.stream().anyMatch(url -> !seen.contains(url))) details.add("FURTHER_PAGES_UNPROCESSED: detail budget exhausted");
            details.addAll(source.diagnostics());
            if(!parsed.select("[aria-expanded=false], details:not([open])").isEmpty()) details.add("COLLAPSIBLE_ELEMENTS_FOUND: "+parsed.select("[aria-expanded=false], details:not([open])").size());
            return new Found(source.url(),source.extraction(),source.fetchMethod(),details.stream().distinct().toList(),source.siteName());
        }).toList();
    }
    private List<URI> portalLinks(EventPageFetcher.Page page,URI root) {
        var result=new LinkedHashSet<URI>();
        for (var link:Jsoup.parse(page.body(),Objects.requireNonNull(page.url().toString())).select("a[href]").stream().limit(200).toList()) {
            try {
                URI next=SafeUrls.parse(CanonicalSourceUrl.of(link.absUrl("href").split("#",2)[0]));
                if (!next.getHost().equalsIgnoreCase(root.getHost()) || next.equals(page.url()) || PartyOnlyPolicy.concertLink(link.text(),next)) continue;
                if (link.attr("title").matches("(?i)^event:.*") || next.getPath().matches("(?i).*/events/[^/]+/?")) result.add(next);
            } catch (IllegalArgumentException ignored) { /* No private, foreign or malformed event targets. */ }
        }
        return List.copyOf(result);
    }
    private boolean hasStaticProgrammeLink(EventPageFetcher.Page page) {
        for (var link:Jsoup.parse(page.body(),Objects.requireNonNull(page.url().toString())).select("a[href]").stream().limit(200).toList()) {
            String label=EventNormalizer.text(link.text().isBlank() ? link.attr("aria-label") : link.text());
            if (!label.matches("events|party|partys|parties|programm|kalender|veranstaltungen|calendar|upcoming events")) continue;
            try {
                URI next=SafeUrls.parse(CanonicalSourceUrl.of(link.absUrl("href").split("#",2)[0]));
                if (!next.equals(page.url()) && next.getHost().equalsIgnoreCase(page.url().getHost())) return true;
            } catch (IllegalArgumentException ignored) { /* Not a permitted navigation target. */ }
        }
        return false;
    }
    /** Preserve overview evidence, including date conflicts and cancellation, when adding detail data. */
    private List<@NonNull Found> enrich(List<@NonNull Found> found, Set<URI> eligible) {
        Map<URI, Found> pages = new HashMap<>();
        found.forEach(f -> pages.put(f.url(), f));
        Map<URI,List<EventExtractor.@NonNull Event>> mergedDetails = new HashMap<>();
        List<@NonNull Found> result = new ArrayList<>();
        for (Found page : found) {
            List<EventExtractor.@NonNull Event> events = new ArrayList<>();
            for (var event : page.extraction().events()) {
                URI url;
                try { url = SafeUrls.parse(page.url().resolve(event.url()).toString().split("#", 2)[0]); }
                catch (IllegalArgumentException e) { events.add(event); continue; }
                if (!eligible.contains(url) || url.equals(page.url())) { events.add(event); continue; }
                Found detail = pages.get(url);
                var matching = detail == null ? List.<EventExtractor.@NonNull Event>of() : detail.extraction().events().stream()
                        .filter(e -> EventNormalizer.text(e.title()).equals(EventNormalizer.text(event.title()))).toList();
                if (matching.size() != 1) {
                    events.add(new EventExtractor.Event(event.identity(), event.title(), event.url(), event.starts(),
                            event.evidence(), event.state() + " UNCONFIRMED Detailseite nicht eindeutig auswertbar", event.location(),event.place()));
                    continue;
                }
                var d = Objects.requireNonNull(matching.get(0), "Detailveranstaltung fehlt");
                List<@NonNull String> starts = new ArrayList<>(event.starts()); starts.addAll(d.starts());
                var declared=new LinkedHashSet<String>(GenreCoverage.declaredGenres(event.evidence()));declared.addAll(GenreCoverage.declaredGenres(d.evidence()));
                String evidence = (declared.isEmpty()?"":"Genres: "+String.join(", ",declared)+"\n")+"Übersicht: " + event.evidence() + "\nDetailseite " + url + ": " + d.evidence();
                String state = event.state() + " " + d.state();
                if(starts.stream().map(EventNormalizer::date).filter(Objects::nonNull).distinct().count()>1
                    || starts.stream().map(EventNormalizer::time).filter(Objects::nonNull).distinct().count()>1) state += " SOURCE_CONFLICT";
                if (!event.location().isBlank() && !d.location().isBlank()
                        && !EventNormalizer.text(event.location()).equals(EventNormalizer.text(d.location()))) state += " UNCONFIRMED Location-Widerspruch";
                var merged = new EventExtractor.Event(event.identity().isBlank() ? url.toString() : event.identity(),
                        event.title(), url.toString(), starts.stream().distinct().toList(),
                        evidence.substring(0, Math.min(evidence.length(), 8000)), state,
                        d.location().isBlank() ? event.location() : d.location(),event.place().merge(d.place()));
                events.add(merged); mergedDetails.computeIfAbsent(url,key -> new ArrayList<>()).add(merged);
            }
            result.add(page.withExtraction(new EventExtractor.Extraction(events, page.extraction().status(),page.extraction().complete())));
        }
        // Both source records use the same merged identity/evidence, preventing duplicate proposals.
        return result.stream().map(f -> {
            var merged=mergedDetails.get(f.url());
            if (merged!=null)
                return f.withExtraction(new EventExtractor.Extraction(merged.stream().distinct().toList(), "EVENTS_FOUND",f.extraction().complete()));
            if (eligible.contains(f.url()))
                return f.withExtraction(new EventExtractor.Extraction(f.extraction().events().stream().map(e ->
                        new EventExtractor.Event(e.identity(), e.title(), e.url(), e.starts(), e.evidence(),
                                e.state() + " UNCONFIRMED Detailzuordnung unklar", e.location(),e.place())).toList(), f.extraction().status(),f.extraction().complete()));
            return f;
        }).toList();
    }
}
