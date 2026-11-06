package de.noizycologne.backend.discovery;

import java.net.URI;
import java.util.regex.Pattern;

/** Concert events are excluded; a separately titled aftershow party is evaluated on its own. */
final class PartyOnlyPolicy {
    private static final Pattern CONCERT = Pattern.compile("\\b([\\p{L}]*konzert(e)?|concert|concerts|tournee|tribute show)\\b");
    static boolean concert(String title, String evidence) {
        String name=EventNormalizer.text(title);
        if (name.matches(".*\\b(aftershow ?party|after ?party) (nach dem konzert|after (the )?(concert|show))\\b.*")
                && !CONCERT.matcher(name.substring(0,name.indexOf("after"))).find()) return false;
        if (CONCERT.matcher(name).find()) return true;
        // A separately titled aftershow party is not the concert mentioned in its description.
        if (name.matches(".*\\b(aftershow ?party|after ?party)\\b.*")) return false;
        String context=EventNormalizer.text(evidence);
        if (name.matches(".*\\b(party|clubnacht)\\b.*") && context.matches(".*\\b(beginn|start) (ist |ab )?(nach dem konzert|after the concert)\\b.*")) return false;
        return CONCERT.matcher(EventNormalizer.text(title + " " + evidence)).find();
    }
    static boolean concert(EventExtractor.Event event) { return concert(event.title(),event.evidence()); }
    static boolean separateAftershow(org.jsoup.nodes.Document doc) {
        return doc.select("h2,h3,[itemprop=name]").stream().anyMatch(heading ->
            EventNormalizer.text(heading.text()).matches(".*\\b(aftershow ?party|after ?party)\\b.*") && !concert(heading.text(),""));
    }
    static boolean concertPage(org.jsoup.nodes.Document doc) {
        String title=EventNormalizer.text(doc.title());
        var h1=doc.selectFirst("main h1, h1");
        String heading=h1==null ? "" : EventNormalizer.text(h1.text());
        boolean mixed=(title+" "+heading).matches(".*\\b(party|partys|parties|clubnacht)\\b.*");
        return !mixed && (title.matches("^(konzerte|concerts|concert|live shows)\\b.*")
                || heading.matches("^(konzerte|concerts|concert|live shows)\\b.*"));
    }

    static boolean concertLink(String label, URI url) {
        return EventNormalizer.text(label).matches("live|live shows|konzerte|konzert|concert|concerts")
                || url.getPath().matches("(?i).*/(konzerte|concerts|live-shows)/?");
    }
    private PartyOnlyPolicy() { }
}
