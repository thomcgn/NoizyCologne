package de.noizycologne.backend.instagram;

import java.util.Objects;

/** Ergebnis der Eligibility-Prüfung; "deny" ist immer der sichere Default. */
public record PublishDecision(boolean allowed, String reason) {
    public PublishDecision {
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static PublishDecision allow() {
        return new PublishDecision(true, "ALLOWED");
    }

    public static PublishDecision deny(String reason) {
        return new PublishDecision(false, reason);
    }
}
