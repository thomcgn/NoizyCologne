package de.noizycologne.backend.discovery;

/** Keep an explicit relative start as text; never derive a clock time from a concert. */
final class EventTiming {
    private EventTiming() { }
    static String relativeStart(EventExtractor.Event event) {
        String text=EventNormalizer.text(event.title()+" "+event.evidence());
        if (text.matches(".*\\b(nach dem konzert|im anschluss an das konzert|after (the )?(concert|show))\\b.*")) return "nach dem Konzert";
        return "";
    }
}
