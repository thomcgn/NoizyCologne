package de.noizycologne.backend.instagram.gates;

import de.noizycologne.backend.instagram.GateResult;
import de.noizycologne.backend.instagram.PublishContext;

import java.util.Map;
import java.util.Objects;

/** Serverseitiges Ergebnis aller Pflicht-Gates inklusive Kontext und Belegen. */
public record GateEvaluation(
        Map<String, GateResult> gateResults,
        Map<String, String> evidence,
        PublishContext context) {

    public GateEvaluation {
        gateResults = Map.copyOf(Objects.requireNonNull(gateResults, "gateResults"));
        evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(context, "context");
    }

    public static GateEvaluation of(Map<String, GateResult> gates, Map<String, String> evidence,
                                    PublishContext context) {
        return new GateEvaluation(gates, evidence, context);
    }
}
