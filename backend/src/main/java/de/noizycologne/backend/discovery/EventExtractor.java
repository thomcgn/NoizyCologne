package de.noizycologne.backend.discovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Component
public class EventExtractor {
    public record Profile(String itemSelector, String titleSelector, String dateSelector) {
        public static Profile defaults() { return new Profile("", "", ""); }
    }
    public record Event(String identity, String title, String url, List<String> starts,
                        String evidence, String state, String location) { }
    public record Extraction(List<Event> events, String status) { }
    private final JsonMapper json = JsonMapper.builder().build();
    public Extraction extract(EventPageFetcher.Page page, Profile profile) {
        List<Event> events = new ArrayList<>();
        boolean malformed = false;
        if (page.body().contains("BEGIN:VCALENDAR")) return ics(page);
        var doc = Jsoup.parse(page.body(), page.url().toString());
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
            String selector = profile.itemSelector().isBlank()
                    ? "[data-hook=event-list-item], [itemscope][itemtype*=Event], article:has(time), article:has(a[href]), .event:has(time), .event:has(a[href]), .event-item, .event-list-item"
                    : profile.itemSelector();
            for (Element item : doc.select(selector).stream().limit(200).toList()) {
                var title = item.selectFirst(profile.titleSelector().isBlank() ? "[data-hook=ev-list-item-title], [itemprop=name], h1, h2, h3, .title" : profile.titleSelector());
                if (title == null) title = item.selectFirst("a[href]");
                if (title == null || title.text().isBlank()) { malformed = true; continue; }
                String dateSelector = profile.dateSelector().isBlank() ? "time:not([itemprop=endDate]), [itemprop=startDate], [data-hook=ev-full-date-location] [data-hook=date]" : profile.dateSelector();
                List<String> dates = item.select(dateSelector).stream().flatMap(e -> {
                    if (e.hasAttr("datetime")) return java.util.stream.Stream.of(e.attr("datetime"));
                    if (e.hasAttr("content")) return java.util.stream.Stream.of(e.attr("content"));
                    return GermanProgramText.starts(e.text()).stream();
                }).distinct().toList();
                var anchor = item.selectFirst("a[data-anchor=event-details][href], a[data-hook=ev-rsvp-button][href]");
                if (anchor == null) anchor = item.selectFirst("a[href]");
                String url = anchor == null ? page.url().toString() : anchor.absUrl("href");
                String state = riskyState(item.text());
                events.add(new Event(url.equals(page.url().toString()) ? "" : url, clip(title.text(), 500), url,
                        dates, clip(item.text(), 8000), state, ""));
            }
            // Generic detail pages with a semantic start date.
            if (events.isEmpty() && doc.selectFirst("h1") != null && doc.selectFirst("time[datetime], [itemprop=startDate]") != null) {
                var date = doc.selectFirst("[itemprop=startDate], time[datetime]");
                events.add(new Event(page.url().toString(), clip(doc.selectFirst("h1").text(), 500), page.url().toString(),
                        List.of(date.hasAttr("datetime") ? date.attr("datetime") : date.attr("content")),
                        clip(doc.body().text(), 8000), riskyState(doc.body().text()), ""));
            }
        }
        if (profile.itemSelector().isBlank()) {
            for (var event : GermanProgramText.events(doc, page.url().toString())) {
                if (events.size() >= 200) break;
                events.add(event);
            }
        }
        boolean concerts = events.stream().anyMatch(PartyOnlyPolicy::concert);
        events.removeIf(PartyOnlyPolicy::concert);
        String status = !events.isEmpty() ? "EVENTS_FOUND" : concerts ? "EXCLUDED_CONCERTS" : malformed ? "UNINTERPRETABLE"
                : doc.body().text().matches("(?is).*(keine (veranstaltungen|events|termine)|no (upcoming )?events).*") ? "NO_EVENTS"
                : requiresRendering(doc) ? "JAVASCRIPT_REQUIRED" : "UNINTERPRETABLE";
        return new Extraction(events, status);
    }
    private boolean requiresRendering(org.jsoup.nodes.Document doc) {
        if (doc.selectFirst("iframe[data-src], iframe:not([src])[data-widget], [data-requires-javascript]") != null) return true;
        // Wix publicly describes current page components as inert JSON. Check only components
        // actually present in the DOM, not its global catalogue of installed apps.
        var warmup = doc.selectFirst("script#wix-warmup-data[type=application/json]");
        if (warmup != null) {
            try {
                var types = json.readTree(warmup.data()).path("pages").path("compIdToTypeMap");
                for (String id : types.propertyNames()) {
                    var element = doc.getElementById(id);
                    if (types.path(id).asText("").equals("HtmlComponent") && element != null
                            && element.text().isBlank() && element.selectFirst("iframe[src], a[href], time, [itemprop=startDate]") == null)
                        return true;
                }
            } catch (RuntimeException ignored) { /* Invalid metadata never becomes executable content. */ }
        }
        return doc.selectFirst("script[src]") != null && doc.body().text().length() < 250;
    }
    private void collect(JsonNode node, List<Event> events, String page, int depth) {
        if (node == null || depth > 16 || events.size() >= 200) return;
        if (node.isArray()) { for (var child : node) collect(child, events, page, depth + 1); return; }
        if (!node.isObject()) return;
        String type = node.path("@type").toString();
        if (type.contains("Event")) {
            String title = node.path("name").asText("");
            if (!title.isBlank()) {
                List<String> starts = new ArrayList<>();
                var start = node.path("startDate");
                if (start.isArray()) for (var value : start) starts.add(value.asText());
                else if (start.isTextual()) starts.add(start.asText());
                String url = java.net.URI.create(page).resolve(node.path("url").asText(page)).toString();
                String state = node.path("eventStatus").asText("") + " " + riskyState(node.path("description").asText(""));
                if (node.has("previousStartDate")) state += " RESCHEDULED " + node.path("previousStartDate").asText();
                events.add(new Event(node.path("@id").asText(url.equals(page) ? "" : url), clip(title, 500), url, starts,
                        clip(node.toString(), 8000), state, node.path("location").path("name").asText("")));
            }
        }
        for (String key : List.of("@graph", "itemListElement", "item", "subEvent", "events"))
            if (node.has(key)) collect(node.get(key), events, page, depth + 1);
    }
    private Extraction ics(EventPageFetcher.Page page) {
        List<Event> events = new ArrayList<>();
        Map<String, List<String>> fields = null;
        for (String line : page.body().replaceAll("\\r?\\n[ \\t]", "").split("\\R")) {
            if (line.equals("BEGIN:VEVENT")) fields = new LinkedHashMap<>();
            else if (line.equals("END:VEVENT") && fields != null) {
                if (events.size() >= 200) break;
                String title = first(fields, "SUMMARY");
                if (!title.isBlank()) {
                    List<String> starts = fields.getOrDefault("DTSTART", List.of()).stream().map(value -> {
                        try {
                            if (value.matches("\\d{8}T\\d{6}Z")) return java.time.ZonedDateTime.of(
                                    java.time.LocalDateTime.parse(value.substring(0, 15), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")),
                                    java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Europe/Berlin")).toLocalDateTime().toString();
                            if (value.matches("\\d{8}T\\d{6}")) return java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toString();
                            return value;
                        } catch (RuntimeException e) { return value; }
                    }).toList();
                    String state = first(fields, "STATUS") + " " + riskyState(first(fields, "DESCRIPTION"));
                    if (fields.containsKey("UNSUPPORTED_TZ")) state += " UNCONFIRMED";
                    events.add(new Event(first(fields, "UID"), clip(title, 500), fields.containsKey("URL") ? first(fields, "URL") : page.url().toString(),
                            starts, clip(fields.toString(), 8000), state, first(fields, "LOCATION")));
                }
                fields = null;
            } else if (fields != null && line.contains(":")) {
                int colon = line.indexOf(':'); String header = line.substring(0, colon);
                String key = header.split(";", 2)[0];
                fields.computeIfAbsent(key, k -> new ArrayList<>()).add(line.substring(colon + 1).replace("\\n", "\n").replace("\\,", ","));
                if (header.contains("TZID=") && !header.contains("TZID=Europe/Berlin")) fields.put("UNSUPPORTED_TZ", List.of(header));
            }
        }
        boolean concerts = events.stream().anyMatch(PartyOnlyPolicy::concert);
        events.removeIf(PartyOnlyPolicy::concert);
        return new Extraction(events, events.isEmpty() ? (concerts ? "EXCLUDED_CONCERTS" : !page.body().contains("BEGIN:VEVENT") && page.body().contains("END:VCALENDAR") ? "NO_EVENTS" : "UNINTERPRETABLE") : "EVENTS_FOUND");
    }
    private static String first(Map<String, List<String>> map, String key) { return map.getOrDefault(key, List.of("")).get(0); }
    private static String riskyState(String text) {
        return java.util.regex.Pattern.compile("(?i)abgesagt|fällt aus|faellt aus|cancelled|canceled|verschoben|rescheduled|unbestätigt|tba|freitag\\s*(oder|/|und)\\s*samstag").matcher(text).find() ? "UNCONFIRMED" : "";
    }
    private static String clip(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }
}
