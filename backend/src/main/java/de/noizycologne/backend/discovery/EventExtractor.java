package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Component
public class EventExtractor {
    public record Profile(@NonNull String itemSelector, @NonNull String titleSelector, @NonNull String dateSelector) {
        public Profile(@Nullable String itemSelector, @Nullable String titleSelector, @Nullable String dateSelector) {
            this.itemSelector = itemSelector == null ? "" : itemSelector;
            this.titleSelector = titleSelector == null ? "" : titleSelector;
            this.dateSelector = dateSelector == null ? "" : dateSelector;
        }
        public static Profile defaults() { return new Profile("", "", ""); }
    }
    public record Event(String identity, String title, String url, List<@NonNull String> starts,
                        String evidence, String state, String location) { }
    public record Extraction(List<@org.jspecify.annotations.NonNull Event> events, String status, boolean complete) {
        public Extraction(List<@org.jspecify.annotations.NonNull Event> events,String status) { this(events,status,false); }
    }
    private final JsonMapper json = JsonMapper.builder().build();
    public Extraction extract(EventPageFetcher.Page page, Profile profile) {
        List<@org.jspecify.annotations.NonNull Event> events = new ArrayList<>();
        boolean malformed = false;
        if (page.body().contains("BEGIN:VCALENDAR")) return ics(page);
        var doc = Jsoup.parse(page.body(), Objects.requireNonNull(page.url().toString()));
        if (PartyOnlyPolicy.concertPage(doc) || PartyOnlyPolicy.concertLink("",page.url())) return new Extraction(List.of(), "EXCLUDED_CONCERTS");
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try { collect(json.readTree(script.data()), events, page.url().toString(), 0); }
            catch (RuntimeException e) { malformed = true; }
        }
        if (page.contentType().contains("json")) {
            try { collect(json.readTree(page.body()), events, page.url().toString(), 0); }
            catch (RuntimeException e) { malformed = true; }
        }
        if (events.isEmpty()) {
            @NonNull String selector = profile.itemSelector().isBlank()
                    ? "[data-hook=event-list-item], [itemscope][itemtype*=Event], article:has(time), article:has(a[href]), .event:has(time), .event:has(a[href]), .event-item, .event-list-item"
                    : profile.itemSelector();
            for (Element item : doc.select(selector).stream().limit(200).toList()) {
                var title = item.selectFirst(profile.titleSelector().isBlank() ? "[data-hook=ev-list-item-title], [itemprop=name], h1, h2, h3, .title" : profile.titleSelector());
                if (title == null) title = item.selectFirst("a[href]");
                if (title == null || title.text().isBlank()) { malformed = true; continue; }
                @NonNull String dateSelector = profile.dateSelector().isBlank() ? "time:not([itemprop=endDate]), [itemprop=startDate], [data-hook=ev-full-date-location] [data-hook=date]" : profile.dateSelector();
                List<@NonNull String> dates = item.select(dateSelector).stream().flatMap(e -> {
                    if (e.hasAttr("datetime")) return java.util.stream.Stream.of(e.attr("datetime"));
                    if (e.hasAttr("content")) return java.util.stream.Stream.of(e.attr("content"));
                    return GermanProgramText.starts(e.text()).stream();
                }).distinct().toList();
                var anchor = item.selectFirst("a[data-anchor=event-details][href], a[data-hook=ev-rsvp-button][href]");
                if (anchor == null) anchor = item.selectFirst("a[href]");
                String url = anchor == null ? page.url().toString() : anchor.absUrl("href");
                String state = riskyState(item.text());
                events.add(new Event(!item.attr("data-event-id").isBlank() ? item.attr("data-event-id") : url.equals(page.url().toString()) ? "" : url, clip(title.text(), 500), url,
                        dates, clip(item.text(), 8000), state, ""));
            }
            // Generic detail pages with a semantic start date.
            var heading = doc.selectFirst("h1");
            var date = doc.selectFirst("[itemprop=startDate], time[datetime]");
            if (events.isEmpty() && heading != null && date != null) {
                String detailUrl=page.url().toString();
                var canonical=doc.selectFirst("link[rel=canonical][href]");
                if(canonical!=null) try {
                    var resolved=SafeUrls.parse(canonical.absUrl("href"));
                    if(resolved.getHost().equalsIgnoreCase(page.url().getHost())) detailUrl=resolved.toString();
                } catch(IllegalArgumentException ignored) { /* Untrusted canonical links cannot change event identity. */ }
                events.add(new Event(detailUrl, clip(heading.text(), 500), detailUrl,
                        List.of(date.hasAttr("datetime") ? date.attr("datetime") : date.attr("content")),
                        clip(doc.body().text(), 8000), riskyState(doc.body().text()), ""));
            }
        }
        if (events.isEmpty() && profile.itemSelector().isBlank()) genericCards(doc,page.url().toString(),events);
        if (profile.itemSelector().isBlank()) {
            for (var event : GermanProgramText.events(doc, page.url().toString())) {
                if (events.size() >= 200) break;
                events.add(event);
            }
        }
        boolean concerts = events.stream().anyMatch(PartyOnlyPolicy::concert);
        events.removeIf(PartyOnlyPolicy::concert);
        String status = !events.isEmpty() ? "EVENTS_FOUND" : concerts ? "EXCLUDED_CONCERTS" : malformed ? "UNINTERPRETABLE"
                : requiresRendering(doc) ? "JAVASCRIPT_REQUIRED"
                : doc.body().text().matches("(?is).*(keine (veranstaltungen|events|termine)|no (upcoming )?events).*") ? "NO_EVENTS" : "UNINTERPRETABLE";
        return new Extraction(events, status);
    }
    boolean needsRendering(EventPageFetcher.Page page,Extraction extraction) {
        return !List.of("EXCLUDED_CONCERTS","NO_EVENTS").contains(extraction.status())
            && extraction.events().stream().noneMatch(event -> !event.title().isBlank()
            && event.starts().stream().anyMatch(start -> EventNormalizer.date(start)!=null))
            && requiresRendering(Jsoup.parse(page.body()));
    }
    private void genericCards(org.jsoup.nodes.Document doc,String page,List<@NonNull Event> events) {
        doc=doc.clone();
        doc.select("footer, nav, script, style, [hidden], [aria-hidden=true]").remove();
        for (var heading:doc.select("h2, h3, h4").stream().limit(200).toList()) {
            Element container=heading.parent();
            for (int depth=0;container!=null && depth<3;depth++,container=container.parent()) {
                if (container.select("h2, h3").size()>1) break;
                List<@NonNull String> starts=new ArrayList<>();
                for (var date:container.select("time[datetime], [itemprop=startDate][content]"))
                    starts.add(date.hasAttr("datetime") ? date.attr("datetime") : date.attr("content"));
                if (starts.isEmpty()) starts.addAll(GermanProgramText.starts(container.text()).stream()
                    .filter(start -> EventNormalizer.date(start)!=null).toList());
                if (starts.isEmpty() || heading.text().isBlank()) continue;
                var link=container.selectFirst("a[href]");
                String url=link==null ? page : link.absUrl("href");
                events.add(new Event(url.equals(page) ? "" : url,clip(heading.text(),500),url,
                    starts.stream().distinct().toList(),clip(container.text(),8000),riskyState(container.text()),""));
                break;
            }
        }
    }
    private boolean requiresRendering(org.jsoup.nodes.Document doc) {
        if (doc.selectFirst("#root, #app, #__next, [data-reactroot], script#__NEXT_DATA__, script[src*='/_next/']")!=null) return true;
        if (doc.selectFirst("iframe[data-src], iframe:not([src])[data-widget], [data-requires-javascript]") != null) return true;
        // Wix publicly describes current page components as inert JSON. Check only components
        // actually present in the DOM, not its global catalogue of installed apps.
        var warmup = doc.selectFirst("script#wix-warmup-data[type=application/json]");
        if (warmup != null) {
            try {
                var types = json.readTree(warmup.data()).path("pages").path("compIdToTypeMap");
                for (String id : types.propertyNames()) {
                    var element = doc.getElementById(Objects.requireNonNull(id, "JSON-Objektschlüssel fehlt"));
                    if (types.path(id).asString("").equals("HtmlComponent") && element != null
                            && element.text().isBlank() && element.selectFirst("iframe[src], a[href], time, [itemprop=startDate]") == null)
                        return true;
                }
            } catch (RuntimeException ignored) { /* Invalid metadata never becomes executable content. */ }
        }
        return doc.selectFirst("script[src]") != null && doc.body().text().length() < 250;
    }
    private void collect(JsonNode node, List<@org.jspecify.annotations.NonNull Event> events, String page, int depth) {
        if (node == null || depth > 16 || events.size() >= 200) return;
        if (node.isArray()) { for (var child : node) collect(child, events, page, depth + 1); return; }
        if (!node.isObject()) return;
        String type = node.path("@type").toString();
        if (type.contains("Event")) {
            String title = node.path("name").asString("");
            if (!title.isBlank()) {
                List<@NonNull String> starts = new ArrayList<>();
                var start = node.path("startDate");
                if (start.isArray()) for (var value : start) starts.add(Objects.requireNonNull(value.asString(), "Startdatumswert fehlt"));
                else if (start.isString()) starts.add(Objects.requireNonNull(start.asString(), "Startdatumswert fehlt"));
                String url = java.net.URI.create(page).resolve(node.path("url").asString(page)).toString();
                String state = node.path("eventStatus").asString("") + " " + riskyState(title + ". " + node.path("description").asString(""));
                if (node.has("previousStartDate")) state += " RESCHEDULED " + node.path("previousStartDate").asString();
                events.add(new Event(node.path("@id").asString(url.equals(page) ? "" : url), clip(title, 500), url, starts,
                        clip(node.toString(), 8000), state, node.path("location").path("name").asString("")));
            }
        }
        for (String key : List.of("@graph", "itemListElement", "item", "subEvent", "events"))
            if (node.has(key)) collect(node.get(key), events, page, depth + 1);
    }
    private Extraction ics(EventPageFetcher.Page page) {
        List<@org.jspecify.annotations.NonNull Event> events = new ArrayList<>();
        Map<String, List<@NonNull String>> fields = null;
        for (String line : page.body().replaceAll("\\r?\\n[ \\t]", "").split("\\R")) {
            if (line.equals("BEGIN:VEVENT")) fields = new LinkedHashMap<>();
            else if (line.equals("END:VEVENT") && fields != null) {
                if (events.size() >= 200) break;
                String title = first(fields, "SUMMARY");
                if (!title.isBlank()) {
                    List<@NonNull String> starts = fields.getOrDefault("DTSTART", List.of()).stream().map(value -> {
                        try {
                            if (value.matches("\\d{8}T\\d{6}Z")) return Objects.requireNonNull(java.time.ZonedDateTime.of(
                                    java.time.LocalDateTime.parse(value.substring(0, 15), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")),
                                    java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Europe/Berlin")).toLocalDateTime().toString());
                            if (value.matches("\\d{8}T\\d{6}")) return Objects.requireNonNull(java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toString());
                            return value;
                        } catch (RuntimeException e) { return value; }
                    }).toList();
                    String state = first(fields, "STATUS") + " " + riskyState(title + ". " + first(fields, "DESCRIPTION"));
                    if (fields.containsKey("UNSUPPORTED_TZ")) state += " SPECULATIVE_TIMEZONE UNCONFIRMED";
                    events.add(new Event(first(fields, "UID"), clip(title, 500), fields.containsKey("URL") ? first(fields, "URL") : page.url().toString(),
                            starts, clip(fields.toString(), 8000), state, first(fields, "LOCATION")));
                }
                fields = null;
            } else if (fields != null && line.contains(":")) {
                int colon = line.indexOf(':'); String header = line.substring(0, colon);
                String key = header.split(";", 2)[0];
                fields.computeIfAbsent(key, k -> new ArrayList<>()).add(Objects.requireNonNull(line.substring(colon + 1).replace("\\n", "\n").replace("\\,", ",")));
                if (header.contains("TZID=") && !header.contains("TZID=Europe/Berlin")) fields.put("UNSUPPORTED_TZ", List.of(header));
            }
        }
        boolean complete=page.body().contains("END:VCALENDAR") && fields==null && events.size()<200
            && page.body().lines().filter(line -> line.equals("BEGIN:VEVENT")).count()==events.size()
            && events.stream().allMatch(event -> !event.starts().isEmpty() && event.starts().stream().allMatch(start -> EventNormalizer.date(start)!=null));
        boolean concerts = events.stream().anyMatch(PartyOnlyPolicy::concert);
        events.removeIf(PartyOnlyPolicy::concert);
        return new Extraction(events, events.isEmpty() ? (concerts ? "EXCLUDED_CONCERTS" : !page.body().contains("BEGIN:VEVENT") && page.body().contains("END:VCALENDAR") ? "NO_EVENTS" : "UNINTERPRETABLE") : "EVENTS_FOUND", complete);
    }
    private static String first(Map<String, List<@NonNull String>> map, String key) { return map.getOrDefault(key, List.of("")).get(0); }
    private static String riskyState(String text) {
        return EventLifecycle.stateFromText(text);
    }
    private static String clip(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }
}
