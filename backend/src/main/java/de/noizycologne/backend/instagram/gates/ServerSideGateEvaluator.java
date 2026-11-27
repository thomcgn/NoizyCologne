package de.noizycologne.backend.instagram.gates;

import de.noizycologne.backend.instagram.ConfiguredPublishEligibility;
import de.noizycologne.backend.instagram.GateResult;
import de.noizycologne.backend.instagram.PublishContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Erzeugt aus authentischen Datenbankfakten ein {@link PublishContext} mit dem
 * Ergebnis <em>aller</em> Pflicht-Gates. Fehlende oder falsche Fakten blockieren.
 * Die eigentliche Erlaubnis entscheidet anschließend ausschließlich der
 * {@link de.noizycologne.backend.instagram.PublishEligibilityPort}.
 */
@Component
public class ServerSideGateEvaluator {
    public GateEvaluation evaluate(PublishFacts facts) {
        Map<String, GateResult> results = new LinkedHashMap<>();
        WorkflowGateApprovals approvals = facts.approvals();
        @Nullable String caption = facts.caption();
        @Nullable String venueId = facts.venueId();
        @Nullable String venueHandle = facts.venueInstagram();
        @Nullable LocalDate eventDate = facts.eventDate();

        add(results, "EVENT_DATA_VALIDATED",
                facts.draftApproved()
                        && eventDate != null
                        && facts.startTime() != null
                        && facts.partyId() != null
                        && venueId != null,
                "Termin, Venue und redaktionelle Freigabe müssen geprüft sein");
        add(results, "MEDIA_VALIDATED", facts.mediaValid(),
                "Es muss ein freigegebenes JPEG-Asset vorhanden sein");
        add(results, "CAPTION_APPROVED",
                "APPROVED".equals(facts.draftStatus())
                        && !facts.approvalNeedsReview()
                        && caption != null && !caption.isBlank()
                        && approvals.captionStaleCheck(),
                "Caption muss freigegeben und nach Stammdatenänderungen geprüft sein");
        add(results, "POST_ORDER_SELECTED",
                facts.positionUnique() && approvals.postOrderSelected(),
                "Eindeutige Reihenfolge muss serverseitig freigegeben sein");
        add(results, "VENUE_FEED_DIVERSITY", facts.venueFeedDiversity(),
                "Gleiche Venue darf nicht in drei aufeinanderfolgenden Posts stehen");
        add(results, "WEEKEND_DAY_BALANCE_REVIEWED", approvals.weekendDayBalanceReviewed(),
                "Tagesmix muss redaktionell geprüft sein");
        add(results, "POSTING_PLAN_CURRENT",
                facts.planRevision() > 0 && approvals.postingPlanCurrent()
                        && !facts.blockingChanges() && !facts.cancellation(),
                "Plan muss aktuell sein; Absage und Eventänderungen blockieren");
        add(results, "ADDITIONAL_WORKFLOW_DEFINED", approvals.additionalWorkflowDefined(),
                "Weitere angekündigte Pflichtschritte sind nicht freigegeben");
        add(results, "VENUE_RESOLVED",
                venueId != null && !venueId.isBlank()
                        && venueHandle != null && !venueHandle.isBlank(),
                "Event muss einer geprüften Venue mit Handle zugeordnet sein");
        add(results, "LINEUP_UNAMBIGUOUS", facts.lineupUnambiguous(),
                "Geplante @Mentions müssen dem bestätigten Event-Line-up entsprechen");
        add(results, "HANDLE_REVIEWED",
                facts.handlesFromMasterData() && approvals.handlesReviewed(),
                "IG-Handles müssen aus geprüften Stammdaten stammen");
        add(results, "CAPTION_STALE_CHECK",
                !facts.approvalNeedsReview() && approvals.captionStaleCheck(),
                "Caption muss nach Stammdatenänderungen erneut geprüft sein");

        // Unbekannte Pflicht-Gates blockieren, statt still übersprungen zu werden.
        for (String key : ConfiguredPublishEligibility.REQUIRED_GATES) {
            if (!results.containsKey(key)) {
                add(results, key, false, "Unbekanntes Pflicht-Gate");
            }
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        put(evidence, "draft_id", facts.draftId());
        put(evidence, "calendar_entry_id", facts.calendarEntryId());
        put(evidence, "media_asset_id", facts.mediaAssetId());
        put(evidence, "event_date", eventDate == null ? null : eventDate.toString());
        put(evidence, "venue_id", facts.venueId());
        put(evidence, "plan_revision", facts.planRevision() > 0 ? String.valueOf(facts.planRevision()) : null);
        return new GateEvaluation(results, evidence,
                new PublishContext(facts.draftApproved(), results));
    }

    private static void add(Map<String, GateResult> results, String key, boolean passed, String message) {
        results.put(key, new GateResult(key, passed, passed ? "geprüft" : message));
    }

    private static void put(Map<String, String> evidence, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            evidence.put(key, value);
        }
    }
}
