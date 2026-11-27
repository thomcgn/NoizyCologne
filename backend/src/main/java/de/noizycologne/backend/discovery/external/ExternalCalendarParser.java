package de.noizycologne.backend.discovery.external;

import de.noizycologne.backend.discovery.EventExtractor;
import de.noizycologne.backend.discovery.EventNormalizer;
import de.noizycologne.backend.discovery.EventPageFetcher;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads only inert feed bytes. ICS is normalized by the project's own extractor,
 * so timings, venue evidence, lifecycle signals and the party-only policy are
 * identical to every other calendar source. JSON objects are translated into a
 * minimal VEVENT before that same extractor runs; no script, browser or second
 * network request is involved.
 */
public final class ExternalCalendarParser {
    public static final int MAX_EVENTS = 200;
    public static final int MAX_BODY_CHARS = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ExternalCalendarParser() { }

    public record Parsed(EventExtractor.Extraction extraction, List<@NonNull String> diagnostics) { }

    public static Parsed parse(@NonNull String format,@Nullable String body,@NonNull URI source) {
        if (!"ICS".equals(format) && !"JSON".equals(format)) return failure("UNSUPPORTED_FORMAT", "Nicht unterstütztes Kalenderformat");
        String text=Objects.requireNonNull(body==null ? "" : body);
        if (text.length()>MAX_BODY_CHARS) return failure("RESPONSE_TOO_LARGE", "Kalenderantwort überschreitet das Größenlimit");
        return "ICS".equals(format) ? ics(text,source) : json(text,source);
    }

    private static Parsed ics(@NonNull String text,@NonNull URI source) {
        List<@NonNull String> diagnostics=new ArrayList<>();
        if (!text.contains("BEGIN:VCALENDAR") || !text.contains("END:VCALENDAR")) {
            diagnostics.add("INCOMPLETE_CALENDAR");
            return new Parsed(new EventExtractor.Extraction(List.of(),"UNINTERPRETABLE",false),List.copyOf(diagnostics));
        }
        long declared=text.lines().filter(line -> line.trim().equals("BEGIN:VEVENT")).count();
        if (declared>MAX_EVENTS) diagnostics.add("EVENT_LIMIT");
        var extraction=read(text,source);
        // The shared extractor silently drops VEVENTs without SUMMARY; make that
        // loss visible in diagnostics instead of pretending the feed was complete.
        if (declared<=MAX_EVENTS && declared>extraction.events().size()) diagnostics.add("MISSING_SUMMARY");
        List<EventExtractor.@NonNull Event> events=new ArrayList<>();
        for (var event:extraction.events()) {
            if (event.title().isBlank()) { diagnostics.add("MISSING_SUMMARY"); continue; }
            if (event.starts().isEmpty() || event.starts().stream().noneMatch(start -> EventNormalizer.date(start)!=null)) {
                diagnostics.add("MISSING_DTSTART"); continue;
            }
            if (events.size()>=MAX_EVENTS) { diagnostics.add("EVENT_LIMIT"); break; }
            events.add(event);
        }
        boolean complete=extraction.complete() && events.size()==extraction.events().size() && declared==events.size() && declared<=MAX_EVENTS;
        String status=status(extraction,events,declared,declared==events.size());
        return new Parsed(new EventExtractor.Extraction(List.copyOf(events),status,complete),List.copyOf(diagnostics));
    }

    private static Parsed json(@NonNull String text,@NonNull URI source) {
        List<@NonNull String> diagnostics=new ArrayList<>();
        JsonNode root;
        try { root=JSON.readTree(text); }
        catch (RuntimeException e) { diagnostics.add("INVALID_JSON"); return failure("UNINTERPRETABLE",List.copyOf(diagnostics)); }
        if (!root.isArray()) { diagnostics.add("NOT_AN_ARRAY"); return failure("UNINTERPRETABLE",List.copyOf(diagnostics)); }
        StringBuilder calendar=new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//NoizyCologne//ExternalCalendar//DE\r\n");
        int skipped=0,count=0;
        for (JsonNode node:root) {
            if (!node.isObject()) { skipped++; continue; }
            String title=clip(firstText(node,"title","name","summary"),500);
            String start=date(firstText(node,"date","startDate","start","startsAt","dateTime","begin"));
            if (title.isBlank() || start.isBlank()) { skipped++; continue; }
            if (count>=MAX_EVENTS) { diagnostics.add("EVENT_LIMIT"); break; }
            calendar.append("BEGIN:VEVENT\r\n");
            append(calendar,"UID",clip(firstText(node,"uid","id","@id"),300));
            append(calendar,"DTSTART",start);
            append(calendar,"DTEND",date(firstText(node,"end","endDate","endsAt")));
            append(calendar,"SUMMARY",title);
            append(calendar,"LOCATION",clip(location(node),300));
            append(calendar,"DESCRIPTION",clip(firstText(node,"description","text","notes"),4000));
            append(calendar,"STATUS",clip(firstText(node,"status","eventStatus"),60));
            append(calendar,"URL",clip(sameOrigin(firstText(node,"url","link","href"),source),2048));
            calendar.append("END:VEVENT\r\n");
            count++;
        }
        if (skipped>0) diagnostics.add("INVALID_JSON_ENTRIES: "+skipped);
        calendar.append("END:VCALENDAR\r\n");
        String calendarText=Objects.requireNonNull(calendar.toString());
        var extraction=read(calendarText,source);
        boolean complete=extraction.complete() && skipped==0 && count<=MAX_EVENTS;
        String status=status(extraction,extraction.events(),count,skipped==0);
        return new Parsed(new EventExtractor.Extraction(List.copyOf(extraction.events()),status,complete),List.copyOf(diagnostics));
    }

    private static EventExtractor.Extraction read(@NonNull String text,@NonNull URI source) {
        String body=Objects.requireNonNull(text);
        return new EventExtractor().extract(new EventPageFetcher.Page(source,body,"text/calendar"),EventExtractor.Profile.defaults());
    }

    private static String status(EventExtractor.Extraction source,List<EventExtractor.@NonNull Event> events,long declared,boolean allParsed) {
        if (events.isEmpty()) return declared>0 || !allParsed ? "UNINTERPRETABLE" : "NO_EVENTS";
        if (!allParsed) return "PARTIAL_FAILURE";
        return "EXCLUDED_CONCERTS".equals(source.status()) ? "EXCLUDED_CONCERTS" : "EVENTS_FOUND";
    }

    private static Parsed failure(@NonNull String status,@NonNull String diagnostic) {
        return failure(status,List.of(Objects.requireNonNull(diagnostic)));
    }
    private static Parsed failure(@NonNull String status,List<@NonNull String> diagnostics) {
        return new Parsed(new EventExtractor.Extraction(List.of(),status,false),diagnostics);
    }

    private static void append(StringBuilder target,String key,String value) {
        if (!value.isBlank()) target.append(key).append(':').append(escape(value)).append("\r\n");
    }

    /** ISO and iCalendar dates stay verbatim; EventNormalizer decides the calendar day. */
    private static @NonNull String date(@NonNull String value) {
        return Objects.requireNonNull(value.trim().replace("\r","").replace("\n",""));
    }

    private static @NonNull String location(JsonNode node) {
        JsonNode value=node.path("location");
        if (value.isNull() || value.isMissingNode()) value=node.path("venue");
        if (value.isString()) return Objects.requireNonNull(value.asString(""));
        if (value.isObject()) {
            String name=firstText(value,"name","title");
            if (!name.isBlank()) return name;
            JsonNode address=value.path("address");
            if (address.isString()) return Objects.requireNonNull(address.asString(""));
            if (address.isObject()) return firstText(address,"streetAddress","addressLocality");
        }
        return firstText(node,"locationName","venueName");
    }

    private static @NonNull String firstText(JsonNode node,String... keys) {
        for (String key:keys) {
            JsonNode value=node.path(key);
            if (value.isString()) {
                String text=Objects.requireNonNull(value.asString(""));
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static @NonNull String sameOrigin(String value,URI source) {
        if (value.isBlank()) return "";
        try {
            URI resolved=source.resolve(value);
            String host=resolved.getHost();
            return "https".equalsIgnoreCase(resolved.getScheme()) && host!=null && host.equalsIgnoreCase(source.getHost())
                ? Objects.requireNonNull(resolved.toString()) : "";
        } catch (RuntimeException e) { return ""; }
    }

    private static @NonNull String escape(String value) {
        return Objects.requireNonNull(value.replace("\\","\\\\").replace(";","\\;").replace(",","\\,")
            .replace("\r\n","\\n").replace("\n","\\n").replace("\r","\\n"));
    }

    private static @NonNull String clip(@NonNull String value,int size) {
        return value.length()<=size ? value : Objects.requireNonNull(value.substring(0,size));
    }
}
