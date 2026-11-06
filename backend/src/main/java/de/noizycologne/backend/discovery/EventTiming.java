package de.noizycologne.backend.discovery;

/** Keep an explicit relative start as text; never derive a clock time from a concert. */
public final class EventTiming {
    private EventTiming() { }
    static String relativeStart(EventExtractor.Event event) {
        if (hasRelativeStart(event.title()+" "+event.evidence())) return "nach dem Konzert";
        return "";
    }
    public static boolean hasRelativeStart(String value) {
        return EventNormalizer.text(value).matches(".*\\b(nach dem konzert|im anschluss an das konzert|after (the )?(concert|show))\\b.*");
    }
}
