package de.noizycologne.backend.instagram;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fail-closed Gate-Sketch (docs/PROJECT.md, 17 und 20.8).
 *
 * <p>Der Flag kommt ausschließlich serverseitig aus {@code noizy.publish.enabled}
 * (Umgebungsvariable {@code NOIZY_PUBLISH_ENABLED}, Default {@code false}). Diese
 * Klasse führt keine HTTP-Aufrufe aus, kennt keine Tokens und veröffentlicht nichts
 * – sie entscheidet nur. Ein echter Publisher darf erst nach separater Freigabe
 * entstehen (siehe ADR 0001).</p>
 */
@Component
public final class ConfiguredPublishEligibility implements PublishEligibilityPort {
    public static final List<String> REQUIRED_GATES = List.of(
            "EVENT_DATA_VALIDATED",
            "MEDIA_VALIDATED",
            "CAPTION_APPROVED",
            "POST_ORDER_SELECTED",
            "VENUE_FEED_DIVERSITY",
            "WEEKEND_DAY_BALANCE_REVIEWED",
            "POSTING_PLAN_CURRENT",
            "ADDITIONAL_WORKFLOW_DEFINED",
            "VENUE_RESOLVED",
            "LINEUP_UNAMBIGUOUS",
            "HANDLE_REVIEWED",
            "CAPTION_STALE_CHECK");

    private final boolean publishEnabled;

    public ConfiguredPublishEligibility(
            @Value("${noizy.publish.enabled:${NOIZY_PUBLISH_ENABLED:false}}") @Nullable String publishFlag) {
        this.publishEnabled = parseFlag(publishFlag);
    }

    /** Nur der exakte Wert "true" aktiviert; null, leer oder unbekannt bleibt false. */
    static boolean parseFlag(@Nullable String raw) {
        return "true".equalsIgnoreCase(raw);
    }

    @Override
    public PublishDecision evaluate(PublishContext context) {
        if (!publishEnabled) {
            return PublishDecision.deny("NOIZY_PUBLISH_ENABLED=false – Publishing deaktiviert");
        }
        if (!context.draftApproved()) {
            return PublishDecision.deny("Entwurf ist nicht freigegeben");
        }
        Map<String, GateResult> results = context.gateResults();
        for (String key : REQUIRED_GATES) {
            @Nullable GateResult result = results.get(key);
            if (result == null || !result.passed()) {
                return PublishDecision.deny("Pflicht-Gate " + key + " fehlt oder ist nicht erfüllt");
            }
        }
        return PublishDecision.allow();
    }
}
