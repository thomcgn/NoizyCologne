package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.regex.Pattern;

/** Source signals only: confirmed calendar cancellations still belong to CancellationService. */
public final class EventLifecycle {
    private static final String CANCELLATION = "(?:abgesagt|fällt aus|faellt aus|cancelled|canceled)";
    private static final Pattern CANCEL = Pattern.compile("\\b" + CANCELLATION + "\\b");
    private static final Pattern NEGATED_CANCEL = Pattern.compile(
            "\\b(?:nicht(?: mehr)?|keinesfalls|not)\\s+" + CANCELLATION + "\\b");
    private static final Pattern SUBJECT = Pattern.compile("\\b(veranstaltung|event|party|termin|dj|act|line up|lineup)\\b");
    private static final Pattern UNCERTAIN = Pattern.compile(
            "\\b(vielleicht|gerücht|geruecht|möglicherweise|moeglicherweise|angeblich|eventuell|possibly|rumou?r|might)\\b");

    private EventLifecycle() { }

    public static @NonNull String signal(EventExtractor.Event event) {
        Objects.requireNonNull(event, "Eventdaten fehlen");
        String state = EventNormalizer.text(event.state());
        if (state.matches(".*\\b(cancelled|canceled|eventcancelled|event cancelled)\\b.*")) return "CANCELLED";
        if (state.matches(".*\\b(eventrescheduled|rescheduled)\\b.*")) return "RESCHEDULED";
        if (state.matches(".*\\b(eventpostponed|postponed)\\b.*")) return "POSTPONED";
        if (state.matches(".*\\b(cancellation hint|cancellation unconfirmed)\\b.*")) return "CANCELLATION_UNCONFIRMED";
        if (state.matches(".*\\b(lineup change|line up change)\\b.*")) return "LINEUP_CHANGE";
        return textSignal(event.title() + ". " + event.evidence());
    }

    /** Retains the persisted legacy markers while making genuine date uncertainty explicit. */
    static @NonNull String stateFromText(String text) {
        String signal = textSignal(text);
        return switch (signal) {
            case "CANCELLED" -> "EVENT_CANCELLED UNCONFIRMED";
            case "CANCELLATION_UNCONFIRMED" -> "CANCELLATION_HINT UNCONFIRMED";
            case "POSTPONED" -> "POSTPONED UNCONFIRMED";
            case "RESCHEDULED" -> "RESCHEDULED UNCONFIRMED";
            case "LINEUP_CHANGE" -> "LINEUP_CHANGE UNCONFIRMED";
            default -> EventNormalizer.text(text).matches(
                    ".*\\b(unbestätigt|unbestaetigt|tentative|unter vorbehalt|freitag (oder|und) samstag)\\b.*")
                    ? "SPECULATIVE_DATE UNCONFIRMED" : "";
        };
    }

    private static @NonNull String textSignal(String text) {
        boolean cancellation = false;
        boolean hint = false;
        boolean lineup = false;
        boolean postponed = false;
        boolean rescheduled = false;
        for (String sentence : text.split("(?<=[.!?;\\n])")) {
            String normalized = NEGATED_CANCEL.matcher(EventNormalizer.text(sentence)).replaceAll("");
            var cancel = CANCEL.matcher(normalized);
            while (cancel.find()) {
                String prefix = normalized.substring(0, cancel.start());
                if (djSubject(prefix)) lineup = true;
                else if (sentence.contains("?") || UNCERTAIN.matcher(prefix).find()) hint = true;
                else cancellation = true;
            }
            String moving = normalized.replaceAll("\\b(?:nicht|not) (?:verschoben|postponed|rescheduled)\\b", "");
            var move = Pattern.compile("\\b(verschoben|postponed|rescheduled)\\b").matcher(moving);
            while (move.find()) {
                if (djSubject(moving.substring(0, move.start()))) lineup = true;
                else if (move.group().equals("rescheduled")) rescheduled = true;
                else postponed = true;
            }
        }
        if (cancellation) return "CANCELLED";
        if (hint) return "CANCELLATION_UNCONFIRMED";
        if (rescheduled) return "RESCHEDULED";
        if (postponed) return "POSTPONED";
        return lineup ? "LINEUP_CHANGE" : "ACTIVE";
    }

    private static boolean djSubject(String prefix) {
        String subject = "";
        var matcher = SUBJECT.matcher(prefix);
        while (matcher.find()) subject = matcher.group(1);
        return subject.equals("dj") || subject.equals("act") || subject.equals("line up") || subject.equals("lineup");
    }
}
