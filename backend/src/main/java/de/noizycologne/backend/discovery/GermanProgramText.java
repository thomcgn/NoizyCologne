package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Parses explicit German dates and year-scoped text schedules; never supplies the current year. */
final class GermanProgramText {
    private static final Pattern FULL_DATE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\.\\s*(\\d{1,2}|\\p{L}+)\\.?\\s*(\\d{4})(?:\\s*,?\\s+(\\d{1,2}):(\\d{2}))?(?!\\d)");
    private static final Pattern ROW = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(?:(\\d{4})\\s+|\\s+)(.+)$");
    private static final Pattern YEAR = Pattern.compile("(?:Jahr\\s+)?((?:19|20|21)\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("(?i)(?:Vorschau|Programm|Termine)?\\s*((?:19|20|21)\\d{2})\\s*[/–-]\\s*(\\d{2}|\\d{4})");
    private static final Map<String,Integer> MONTHS = Map.ofEntries(
            Map.entry("jan",1), Map.entry("januar",1), Map.entry("feb",2), Map.entry("februar",2),
            Map.entry("mär",3), Map.entry("märz",3), Map.entry("mrz",3), Map.entry("maerz",3),
            Map.entry("apr",4), Map.entry("april",4), Map.entry("mai",5), Map.entry("jun",6), Map.entry("juni",6),
            Map.entry("jul",7), Map.entry("juli",7), Map.entry("aug",8), Map.entry("august",8),
            Map.entry("sep",9), Map.entry("sept",9), Map.entry("september",9), Map.entry("okt",10), Map.entry("oktober",10),
            Map.entry("nov",11), Map.entry("november",11), Map.entry("dez",12), Map.entry("dezember",12),
            Map.entry("january",1), Map.entry("february",2), Map.entry("march",3), Map.entry("may",5),
            Map.entry("june",6), Map.entry("july",7), Map.entry("oct",10), Map.entry("october",10), Map.entry("dec",12), Map.entry("december",12));

    private static final Pattern ISO_DATE=Pattern.compile("(?<!\\d)(\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2})?)?)(?!\\d)");
    private static final Pattern ENGLISH_DATE=Pattern.compile("(?i)(?:([a-z]+)\\s+(\\d{1,2}),?\\s+(\\d{4})|(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4}))(?:\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2}))?");
    static List<@NonNull String> starts(@NonNull String text) {
        // An explicitly separated range supplies one start; the later date/time is the end.
        String start = clean(text).split("\\s+[–—-]\\s+", 2)[0];
        var matcher = FULL_DATE.matcher(start);
        List<@NonNull String> dates = new ArrayList<>();
        while (matcher.find()) {
            try {
                String monthText = matcher.group(2).toLowerCase(Locale.ROOT);
                int month = monthText.matches("\\d+") ? Integer.parseInt(monthText) : MONTHS.getOrDefault(monthText, 0);
                var date = LocalDate.of(Integer.parseInt(matcher.group(3)), month, Integer.parseInt(matcher.group(1)));
                dates.add(matcher.group(4) == null ? Objects.requireNonNull(date.toString()) : Objects.requireNonNull(date.atTime(
                        LocalTime.of(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)))).toString()));
            } catch (DateTimeException | NumberFormatException e) { dates.add(Objects.requireNonNull(matcher.group(), "Datumsbeleg fehlt")); }
        }
        if (dates.isEmpty()) {
            var iso=ISO_DATE.matcher(start);
            while (iso.find()) dates.add(Objects.requireNonNull(iso.group(1).replace(' ','T')));
        }
        if (dates.isEmpty()) {
            var english=ENGLISH_DATE.matcher(start);
            while (english.find()) try {
                String month=english.group(1)==null ? english.group(5) : english.group(1);
                String day=english.group(1)==null ? english.group(4) : english.group(2);
                String year=english.group(1)==null ? english.group(6) : english.group(3);
                var date=LocalDate.of(Integer.parseInt(year),MONTHS.getOrDefault(month.toLowerCase(Locale.ROOT),0),Integer.parseInt(day));
                dates.add(Objects.requireNonNull(english.group(7)==null ? date.toString() : date.atTime(Integer.parseInt(english.group(7)),Integer.parseInt(english.group(8))).toString()));
            } catch (DateTimeException | NumberFormatException ignored) { /* Unparseable dates never receive an invented year. */ }
        }
        return dates.isEmpty() ? List.of(text) : dates.stream().distinct().toList();
    }

    static List<EventExtractor.@org.jspecify.annotations.NonNull Event> events(Document doc, String page) {
        List<EventExtractor.@org.jspecify.annotations.NonNull Event> events = new ArrayList<>();
        Element parent = null;
        Integer year = null;
        String context = "";
        boolean preview = false;
        for (Element block : doc.select("p, li")) {
            // Context belongs to sibling paragraphs, never to another unrelated page section.
            if (block.parent() != parent) { parent = block.parent(); year = null; context = ""; preview = false; }
            if (block.closest("[data-hook=event-list-item], article, .event, .event-item, .event-list-item") != null) continue;
            String text = clean(block.text());
            if (text.equalsIgnoreCase("VORSCHAU")) preview = true;
            var range = RANGE.matcher(text);
            if (range.matches()) { year = null; context = text; preview |= text.toLowerCase(Locale.ROOT).contains("vorschau"); continue; }
            var heading = YEAR.matcher(text);
            if (heading.matches()) { year = Integer.parseInt(heading.group(1)); context = text; continue; }
            var row = ROW.matcher(text);
            if (!row.matches() || events.size() >= 200) continue;
            String title = row.group(4).trim();
            Integer explicitYear = row.group(3) == null ? year : Integer.valueOf(row.group(3));
            List<@NonNull String> starts = List.of();
            if (explicitYear != null) {
                try { starts = List.of(Objects.requireNonNull(LocalDate.of(explicitYear, Integer.parseInt(row.group(2)), Integer.parseInt(row.group(1))).toString())); }
                catch (DateTimeException ignored) { }
            }
            Element anchor = block.selectFirst("a[href]");
            String url = anchor == null ? page : anchor.absUrl("href");
            String identity = anchor == null ? page + "#text-event-" + EventNormalizer.hash(text + "|" + context) : url;
            String state = preview || explicitYear == null || starts.isEmpty() ? "UNCONFIRMED Textvorschau oder unvollständiges Datum" : "";
            state = (state + " " + EventLifecycle.stateFromText(text)).trim();
            events.add(new EventExtractor.Event(identity, title.substring(0, Math.min(500,title.length())), url,
                    starts, "Jahreskontext: " + context + "; " + text, state, ""));
        }
        return events;
    }
    private static String clean(String text) { return text.replaceAll("\\p{Cf}", "").replace('\u00a0', ' ').trim(); }
}
