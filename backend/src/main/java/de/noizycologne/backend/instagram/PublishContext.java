package de.noizycologne.backend.instagram;

import java.util.Map;
import java.util.Objects;

/** Serverseitiger Kontext für die Gate-Prüfung – bewusst ohne Tokens. */
public record PublishContext(boolean draftApproved, Map<String, GateResult> gateResults) {
    public PublishContext {
        gateResults = Objects.requireNonNull(gateResults, "gateResults");
    }

    public static PublishContext approvedWith(Map<String, GateResult> gateResults) {
        return new PublishContext(true, gateResults);
    }
}
