package de.noizycologne.backend.discovery;

import java.net.URI;
import java.util.regex.Pattern;

/** Concert evidence always overrides a genre or party-series name match. */
final class PartyOnlyPolicy {
    private static final Pattern CONCERT = Pattern.compile("\\b([\\p{L}]*konzert(e)?|concert|concerts|tournee|tribute show)\\b");
    static boolean concert(String title, String evidence) {
        return CONCERT.matcher(EventNormalizer.text(title + " " + evidence)).find();
    }
    static boolean concert(EventExtractor.Event event) { return concert(event.title(),event.evidence()); }
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
