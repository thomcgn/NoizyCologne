package de.noizycologne.backend.discovery;

/** Do not persist externally mentioned health details, even in raw observation evidence. */
public final class EventPrivacy {
    private EventPrivacy() { }
    public static EventExtractor.@org.jspecify.annotations.NonNull Event safe(EventExtractor.Event event) {
        if(event==null) throw new IllegalArgumentException("Eventdaten fehlen");
        boolean privateTitle=health(event.title());
        if (!health(event.evidence()) && !privateTitle) return event;
        return new EventExtractor.Event(event.identity(),privateTitle ? "Veranstaltung – personenbezogene Titelangaben entfernt" : event.title(),event.url(),event.starts(),
            "Personenbezogene Gesundheitsangaben aus dem Quelltext werden nicht gespeichert.",event.state(),event.location(),event.place());
    }
    private static boolean health(String value) {
        return value.matches("(?is).*(krank|erkrank|diagnos|gesundheit|hospital|illness|medical|sick|verletz|cancer|covid|grippe|depress|burnout).*" );
    }
}
