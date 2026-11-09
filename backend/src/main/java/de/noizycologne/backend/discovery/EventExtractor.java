package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.time.*;

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
                        String evidence, String state, String location, EventPlace place) {
        public Event(String identity,String title,String url,List<@NonNull String> starts,String evidence,String state,String location) {
            this(identity,title,url,starts,evidence,state,location,EventPlace.empty());
        }
        public Event {place=place==null?EventPlace.empty():place;}
    }
    public record Extraction(List<@org.jspecify.annotations.NonNull Event> events, String status, boolean complete) {
        public Extraction(List<@org.jspecify.annotations.NonNull Event> events,String status) { this(events,status,false); }
    }
    private final Clock clock;
    public EventExtractor() { this(Clock.systemUTC()); }
    public EventExtractor(Clock clock) { this.clock=Objects.requireNonNull(clock); }
    private record HtmlEvent(Element container,Event event,String label) { }
    private final JsonMapper json = JsonMapper.builder().build();
    public Extraction extract(EventPageFetcher.Page page, Profile profile) {
        List<@org.jspecify.annotations.NonNull Event> events = new ArrayList<>();
        boolean malformed = false;
        if (page.body().contains("BEGIN:VCALENDAR")) return ics(page);
        var doc = Jsoup.parse(page.body(), Objects.requireNonNull(page.url().toString()));
        boolean concertPage=!PartyOnlyPolicy.separateAftershow(doc) && (PartyOnlyPolicy.concertPage(doc) || PartyOnlyPolicy.concertLink("",page.url()));
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try { collect(json.readTree(script.data()), events, page.url().toString(), 0); }
            catch (RuntimeException e) { malformed = true; }
        }
        if (page.contentType().contains("json")) {
            try { collect(json.readTree(page.body()), events, page.url().toString(), 0); }
            catch (RuntimeException e) { malformed = true; }
        }
        List<@NonNull HtmlEvent> htmlEvents=new ArrayList<>();
        {
            @NonNull String selector = profile.itemSelector().isBlank()
                    ? "[data-hook=event-list-item], [itemscope][itemtype*=Event], article:has(time), article:has(a[href]), .event:has(time), .event:has(a[href]), .event-item, .event-list-item, details:has(summary), li:has([aria-expanded]), [role=listitem]:has([aria-expanded])"
                    : profile.itemSelector();
            var containers=new HashSet<>(doc.select(selector));
            for (Element item : doc.select(selector).stream().limit(200).toList()) {
                if(item.parents().stream().anyMatch(parent -> parent.is("nav,header,footer,form,[role=dialog]"))) continue;
                if(item.parents().stream().anyMatch(parent -> containers.contains(parent) && parent.select("h1,h2,h3,h4,[itemprop=name],.title").size()<=1)) continue;
                if(item.select("article, [itemscope][itemtype*=Event]").stream().anyMatch(child -> child!=item && child.selectFirst("h1,h2,h3,h4,[itemprop=name]")!=null)) continue;
                var title = item.selectFirst(profile.titleSelector().isBlank() ? "[data-hook=ev-list-item-title], [itemprop=name], h1, h2, h3, .title, summary, [aria-expanded]" : profile.titleSelector());
                if (title == null) title = item.selectFirst("a[href]");
                if (title == null || title.text().isBlank()) { malformed = true; continue; }
                @NonNull String dateSelector = profile.dateSelector().isBlank() ? "time:not([itemprop=endDate]), [itemprop=startDate], [data-hook=ev-full-date-location] [data-hook=date]" : profile.dateSelector();
                List<@NonNull String> dates = item.select(dateSelector).stream().flatMap(e -> {
                    if (e.hasAttr("datetime")) return java.util.stream.Stream.of(e.attr("datetime"));
                    if (e.hasAttr("content")) return java.util.stream.Stream.of(e.attr("content"));
                    return GermanProgramText.starts(e.text()).stream();
                }).distinct().toList();
                String url = detailUrl(item,page.url().toString());
                String state = riskyState(item.text());
                var label=item.selectFirst(profile.dateSelector().isBlank() ? ".date, "+dateSelector : dateSelector);
                String dateLabel=label==null ? "" : label.text();
                if(dates.isEmpty() && !dateLabel.isBlank()) dates=GermanProgramText.starts(dateLabel);
                htmlEvents.add(new HtmlEvent(item,new Event(!item.attr("data-event-id").isBlank() ? item.attr("data-event-id") : url.equals(page.url().toString()) ? "" : url, clip(title.text(), 500), url,
                        GermanProgramText.eventStarts(dates,item.text()), htmlEvidence(item), state, locationName(item),htmlPlace(item)),dateLabel));
            }
            // Generic detail pages with a semantic start date.
            var heading = doc.selectFirst("h1");
            var date = doc.selectFirst("[itemprop=startDate], time[datetime]");
            if (htmlEvents.isEmpty() && heading != null && date != null) {
                String detailUrl=page.url().toString();
                var canonical=doc.selectFirst("link[rel=canonical][href]");
                if(canonical!=null) try {
                    var resolved=SafeUrls.parse(canonical.absUrl("href"));
                    if(resolved.getHost().equalsIgnoreCase(page.url().getHost())) detailUrl=resolved.toString();
                } catch(IllegalArgumentException ignored) { /* Untrusted canonical links cannot change event identity. */ }
                htmlEvents.add(new HtmlEvent(doc.body(),new Event(detailUrl, clip(heading.text(), 500), detailUrl,
                        GermanProgramText.eventStarts(List.of(date.hasAttr("datetime") ? date.attr("datetime") : date.attr("content")),doc.body().text()),
                        htmlEvidence(doc.body()), riskyState(doc.body().text()), "",htmlPlace(doc)),date.text()));
            }
        }
        if (events.isEmpty() && htmlEvents.isEmpty() && profile.itemSelector().isBlank()) genericCards(doc,page.url().toString(),htmlEvents);
        Map<Element,List<@NonNull HtmlEvent>> lists=new LinkedHashMap<>();
        for(var item:htmlEvents) lists.computeIfAbsent(item.container().parent(),key -> new ArrayList<>()).add(item);
        var fetchedOn=LocalDate.now(clock.withZone(ZoneId.of("Europe/Berlin")));
        Map<HtmlEvent,GermanProgramText.ResolvedDate> resolvedByItem=new IdentityHashMap<>();
        for(var list:lists.values()) {
            var resolved=GermanProgramText.resolve(list.stream().map(item -> new GermanProgramText.DateEvidence(item.label(),item.event().starts())).toList(),fetchedOn,GermanProgramText.calendarContext(Objects.requireNonNull(list.getFirst()).container().parent()));
            for(int i=0;i<list.size();i++) resolvedByItem.put(Objects.requireNonNull(list.get(i)),Objects.requireNonNull(resolved.get(i)));
        }
        // Resolve in local lists, but retain the original document order in the extraction.
        for(var item:htmlEvents) {
            var event=item.event();var day=Objects.requireNonNull(resolvedByItem.get(item));
            events.add(new Event(event.identity(),event.title(),event.url(),day.starts(),event.evidence(),
                event.state()+(day.conflict() ? " SOURCE_CONFLICT: Jahreskontext oder Wochentag widersprüchlich" : ""),event.location(),event.place()));
        }
        if (profile.itemSelector().isBlank()) {
            for (var event : GermanProgramText.events(doc, page.url().toString())) {
                if (events.size() >= 200) break;
                events.add(event);
            }
        }
        events = DiscoveryBatchMerger.merge(events.stream().map(event -> new DiscoveryBatchMerger.Input(page.url().toString(),event,true,true)).toList(), "").stream().limit(200).map(item -> item.origins().size()==1?item.primary().event():item.event()).toList();
        events = new ArrayList<>(events);
        boolean concerts = events.stream().anyMatch(PartyOnlyPolicy::concert);
        boolean concertContext=concertPage && events.stream().noneMatch(event -> EventNormalizer.text(event.title()).matches(".*\\b(aftershow ?party|after ?party)\\b.*") && !PartyOnlyPolicy.concert(event));
        if (concertContext) events.replaceAll(event -> new Event(event.identity(),event.title(),event.url(),event.starts(),event.evidence()+"\nKonzert",event.state(),event.location(),event.place()));
        String status = !events.isEmpty() ? (concertContext || events.stream().allMatch(PartyOnlyPolicy::concert) ? "EXCLUDED_CONCERTS" : "EVENTS_FOUND") : concerts ? "EXCLUDED_CONCERTS" : malformed ? "UNINTERPRETABLE"
                : requiresRendering(doc) ? "JAVASCRIPT_REQUIRED"
                : doc.body().text().matches("(?is).*(keine (veranstaltungen|events|termine)|no (upcoming )?events).*") ? "NO_EVENTS" : "UNINTERPRETABLE";
        return new Extraction(events, status);
    }
    Extraction extractDetail(EventPageFetcher.Page page,Profile profile) {
        var result=extract(page,profile);
        if(!result.events().isEmpty() || page.body().contains("BEGIN:VCALENDAR")) return result;
        var doc=Jsoup.parse(page.body(),Objects.requireNonNull(page.url().toString()));
        doc.select("nav,header,footer,script,style").remove();
        var heading=doc.selectFirst("h1");
        if(heading==null || heading.text().isBlank()) return result;
        var scope=doc.selectFirst("main");if(scope==null)scope=doc.body();
        return new Extraction(List.of(new Event(page.url().toString(),clip(heading.text(),500),page.url().toString(),
            GermanProgramText.starts(scope.text()).stream().filter(start -> EventNormalizer.date(start)!=null).toList(),
            htmlEvidence(scope),riskyState(scope.text()),locationName(scope),htmlPlace(scope))),"EVENTS_FOUND",false);
    }
    boolean needsRendering(EventPageFetcher.Page page,Extraction extraction) {
        var doc=Jsoup.parse(page.body());
        return interactiveList(doc) || (!List.of("EXCLUDED_CONCERTS","NO_EVENTS").contains(extraction.status())
            && extraction.events().stream().noneMatch(event -> !event.title().isBlank()
            && event.starts().stream().anyMatch(start -> EventNormalizer.date(start)!=null)) && requiresRendering(doc));
    }
    static boolean interactiveList(org.jsoup.nodes.Document doc) {
        for(var node:doc.select("button, [role=button]")) {
            if(node.parents().stream().anyMatch(parent -> parent.is("nav,header,footer,form,[role=dialog]"))) continue;
            if(EventNormalizer.text(node.text().isBlank()?node.attr("aria-label"):node.text())
                .matches("mehr (laden|veranstaltungen|events)|load more(?: events)?|next(?: page| month)?|nächste[rns]? (seite|monat)|weiter")) return true;
            if(!node.attr("aria-expanded").equals("false")) continue;
            var card=node.closest("article,li,[role=listitem]");
            if(card==null) continue;
            var panel=doc.getElementById(node.attr("aria-controls"));
            if(panel==null || panel.text().isBlank()) return true;
        }
        return false;
    }
    /** Event-scoped links take precedence over images, share buttons and unrelated navigation. */
    private static String detailUrl(Element card,String page) {
        var links=card.select("a[href]");
        var ordered=new ArrayList<Element>();
        for(var link:links) if(link.hasAttr("data-anchor") && link.attr("data-anchor").equals("event-details")
            || EventNormalizer.text(link.text().isBlank()?link.attr("aria-label"):link.text())
                .matches(".*(?:erfahre hier mehr|mehr erfahren|details|tickets und infos|tickets infos|tickets & infos|event details).*")) ordered.add(link);
        for(var link:links) if(!ordered.contains(link)) ordered.add(link);
        for(var link:ordered) try {
            var target=SafeUrls.parse(link.absUrl("href"));
            if(target.getHost().equalsIgnoreCase(java.net.URI.create(page).getHost())
                && !target.getPath().matches("(?i).*\\.(?:jpg|jpeg|png|webp|pdf)$")
                && !link.hasAttr("download")) return target.toString();
        }catch(IllegalArgumentException ignored) { /* Keep the card identity within this website. */ }
        return page;
    }
    private static String htmlEvidence(Element card) {
        StringBuilder text=new StringBuilder(card.text());
        for(var paragraph:card.select("p,dd,[itemprop=genre]")) {
            if(paragraph.attr("itemprop").equals("genre")) text.append("\nGenres: ").append(paragraph.text());
            else if(paragraph.text().matches("(?is)^(genres?|musikrichtung|styles?)\\s*:.*")) text.append('\n').append(paragraph.text());
        }
        return clip(text.toString(),8000);
    }
    private static String locationName(Element scope) {
        var location=scope.selectFirst("[itemprop=location]");
        if(location==null) return "";
        var name=location.selectFirst("[itemprop=name]");
        return name==null?location.ownText():name.text();
    }
    private void genericCards(org.jsoup.nodes.Document doc,String page,List<@NonNull HtmlEvent> events) {
        doc=doc.clone();
        doc.select("footer, nav, script, style").remove();
        for (var heading:doc.select("h1, h2, h3, h4").stream().limit(200).toList()) {
            Element container=heading.parent();
            for (int depth=0;container!=null && depth<3;depth++,container=container.parent()) {
                if (container.select("h1, h2, h3, h4").size()>1) break;
                // Prefer the card's own date label over a date mentioned inside its description.
                var parent=container.parent();
                if(container.selectFirst(".date, time, [itemprop=startDate]")==null && parent!=null
                        && parent.select("h1, h2, h3, h4").size()==1 && parent.selectFirst(".date, time, [itemprop=startDate]")!=null) continue;
                List<@NonNull String> starts=new ArrayList<>();
                for (var date:container.select("time[datetime], [itemprop=startDate][content]"))
                    starts.add(date.hasAttr("datetime") ? date.attr("datetime") : date.attr("content"));
                if (starts.isEmpty()) starts.addAll(GermanProgramText.starts(container.text()).stream()
                    .filter(start -> EventNormalizer.date(start)!=null).toList());
                // Some server-rendered programme cards use a separate date label without a year.
                // Retain that evidence for review instead of inventing a year or clock time.
                if (starts.isEmpty()) {
                    var label=container.selectFirst(".date");
                    if (label!=null && !label.text().isBlank()) starts.addAll(GermanProgramText.starts(label.text()));
                }
                var dateLabel=container.selectFirst(".date, time, [itemprop=startDate]");
                String label=dateLabel==null ? container.text() : dateLabel.text();
                if(starts.isEmpty() && GermanProgramText.monthDay(label)!=null) starts.addAll(GermanProgramText.starts(label));
                if (starts.isEmpty() && detailUrl(container,page).equals(page) || heading.text().isBlank()) continue;
                String url=detailUrl(container,page);
                events.add(new HtmlEvent(container,new Event(url.equals(page) ? "" : url,clip(heading.text(),500),url,
                    GermanProgramText.eventStarts(starts.stream().distinct().toList(),container.text()),htmlEvidence(container),riskyState(container.text()),locationName(container),htmlPlace(container)),label));
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
                events.add(new Event(node.path("@id").asString(url.equals(page) ? "" : url), clip(title, 500), url, GermanProgramText.eventStarts(starts,node.path("description").asString("")),
                        clip(node.toString(), 8000), state, node.path("location").path("name").asString(""),jsonPlace(node.path("location"),page)));
            }
        }
        for (String key : List.of("@graph", "itemListElement", "item", "subEvent", "events"))
            if (node.has(key)) collect(node.get(key), events, page, depth + 1);
    }
    public @NonNull Event recoverPlace(Event event) {
        Objects.requireNonNull(event,"Eventdaten fehlen");
        if(!event.place().equals(EventPlace.empty())) return event;
        try {
            var node=json.readTree(event.evidence());
            if(node.isObject() && node.path("@type").toString().contains("Event") && node.path("name").asString("").equals(event.title()))
                return new Event(event.identity(),event.title(),event.url(),event.starts(),event.evidence(),event.state(),event.location(),jsonPlace(node.path("location"),event.url()));
        }catch(RuntimeException ignored){/* Legacy free text never proves a city. */}
        return event;
    }
    private EventPlace jsonPlace(JsonNode node,String page) {
        if(node.isArray()) {var place=EventPlace.empty();for(var item:node) place=place.merge(jsonPlace(item,page));return place;}
        if(!node.isObject()) return node.isString()?icsPlace(node.asString(""),""):EventPlace.empty();
        var address=node.path("address");var geo=node.path("geo");
        var textAddress=address.isString()?icsPlace(address.asString(""),""):EventPlace.empty();
        String reference=node.path("@id").asString(node.path("url").asString(""));
        try {reference=reference.isBlank()?"":CanonicalSourceUrl.reference(java.net.URI.create(page).resolve(reference).toString());}
        catch(IllegalArgumentException e){reference="";}
        var country=address.path("addressCountry");
        return new EventPlace(address.path("addressLocality").asString(textAddress.city()),country.isObject()?country.path("name").asString(""):country.asString(""),
            address.path("streetAddress").asString(textAddress.street()),address.path("postalCode").asString(""),reference,number(geo.path("latitude")),number(geo.path("longitude")),false);
    }
    private EventPlace htmlPlace(Element scope) {
        for(var location:scope.select("[itemprop=location]")) {
            boolean organizer=location.parents().stream().takeWhile(parent->parent!=scope).anyMatch(parent->parent.attr("itemprop").matches("organizer|performer|sponsor|seller"));
            if(organizer) continue;
            return new EventPlace(property(location,"addressLocality"),property(location,"addressCountry"),property(location,"streetAddress"),property(location,"postalCode"),"",
                number(property(location,"latitude")),number(property(location,"longitude")),false);
        }
        return EventPlace.empty();
    }
    private String property(Element scope,String property) {var node=scope.selectFirst("[itemprop="+property+"]");return node==null?"":node.hasAttr("content")?node.attr("content"):node.text();}
    private @Nullable Double number(JsonNode node) {return node.isNumber()?number(node.toString()):number(node.asString(""));}
    private @Nullable Double number(String value) {try {double result=Double.parseDouble(value);return Double.isFinite(result)?result:null;}catch(NumberFormatException e){return null;}}
    private EventPlace icsPlace(String location,String geo) {
        // LOCATION is scoped event-address evidence. A street/number and explicit terminal city are required.
        var address=java.util.regex.Pattern.compile("(?s)(?:.*?,\\s*)?([^,]*\\d[^,]*),\\s*(?:[0-9]{5}\\s+)?([\\p{L} .-]+)$").matcher(location);
        String[] point=geo.split(";",2);
        return new EventPlace(address.matches()?address.group(2):"","",address.matches()?address.group(1):"","","",point.length==2?number(point[0]):null,point.length==2?number(point[1]):null,false);
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
                            starts, clip(fields.toString(), 8000), state, first(fields, "LOCATION"),icsPlace(first(fields,"LOCATION"),first(fields,"GEO"))));
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
        return new Extraction(events, !events.isEmpty() && events.stream().allMatch(PartyOnlyPolicy::concert) ? "EXCLUDED_CONCERTS" : events.isEmpty() ? (concerts ? "EXCLUDED_CONCERTS" : !page.body().contains("BEGIN:VEVENT") && page.body().contains("END:VCALENDAR") ? "NO_EVENTS" : "UNINTERPRETABLE") : "EVENTS_FOUND", complete);
    }
    private static String first(Map<String, List<@NonNull String>> map, String key) { return map.getOrDefault(key, List.of("")).get(0); }
    private static String riskyState(String text) {
        return EventLifecycle.stateFromText(text);
    }
    private static String clip(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }
}
