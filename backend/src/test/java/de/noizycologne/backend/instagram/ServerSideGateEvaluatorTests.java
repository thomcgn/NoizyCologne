package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.gates.PublishFacts;
import de.noizycologne.backend.instagram.gates.ServerSideGateEvaluator;
import de.noizycologne.backend.instagram.gates.WorkflowGateApprovals;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ServerSideGateEvaluatorTests {
    private final ServerSideGateEvaluator evaluator = new ServerSideGateEvaluator();

    private static PublishFacts completeFacts() {
        return new PublishFacts(
                "draft-1", "APPROVED", false, "Freigegebene Caption", "#noizycologne",
                "entry-1", "ACTIVE", LocalDate.of(2027, 1, 9), LocalTime.of(22, 0),
                "venue-1", "venue_handle", "party-1", "media-1", true,
                false, false, true, true, true, true, 1, 1, WorkflowGateApprovals.ALL);
    }

    @Test
    void allTwelveGatesPassOnlyWithCompleteFactsAndExplicitApprovals() {
        var evaluation = evaluator.evaluate(completeFacts());

        assertThat(evaluation.gateResults()).hasSize(12);
        assertThat(evaluation.gateResults().values()).allMatch(result ->
                Objects.requireNonNull(result).passed());
        assertThat(evaluation.context().draftApproved()).isTrue();
        assertThat(evaluation.evidence()).containsEntry("draft_id", "draft-1");
        assertThat(new ConfiguredPublishEligibility("true").evaluate(evaluation.context()).allowed()).isTrue();
    }

    @Test
    void additionalWorkflowGateBlocksEvenWhenEveryOtherFactIsComplete() {
        PublishFacts facts = new PublishFacts(
                completeFacts().draftId(), completeFacts().draftStatus(), false,
                completeFacts().caption(), completeFacts().hashtags(), completeFacts().calendarEntryId(),
                completeFacts().eventStatus(), completeFacts().eventDate(), completeFacts().startTime(),
                completeFacts().venueId(), completeFacts().venueInstagram(), completeFacts().partyId(),
                completeFacts().mediaAssetId(), true, false, false, true, true, true, true,
                1, 1, new WorkflowGateApprovals(true, true, true, true, true, false));
        var evaluation = evaluator.evaluate(facts);

        assertThat(failedKeys(evaluation.gateResults())).containsExactly("ADDITIONAL_WORKFLOW_DEFINED");
        assertThat(new ConfiguredPublishEligibility("true").evaluate(evaluation.context()).allowed()).isFalse();
    }

    @Test
    void missingOrFalseFactsBlockTheCorrespondingGates() {
        PublishFacts incomplete = new PublishFacts(
                null, null, true, null, null, null, "PLANNED", null, null,
                null, null, null, null, false, true, true, false, false, false, false,
                0, 0, WorkflowGateApprovals.NONE);
        var evaluation = evaluator.evaluate(incomplete);

        assertThat(evaluation.gateResults()).hasSize(12);
        assertThat(failedKeys(evaluation.gateResults())).containsExactlyInAnyOrder(
                "EVENT_DATA_VALIDATED", "MEDIA_VALIDATED", "CAPTION_APPROVED",
                "POST_ORDER_SELECTED", "VENUE_FEED_DIVERSITY", "WEEKEND_DAY_BALANCE_REVIEWED",
                "POSTING_PLAN_CURRENT", "ADDITIONAL_WORKFLOW_DEFINED", "VENUE_RESOLVED",
                "LINEUP_UNAMBIGUOUS", "HANDLE_REVIEWED", "CAPTION_STALE_CHECK");
        assertThat(evaluation.context().draftApproved()).isFalse();
    }

    @Test
    void cancellationAndEventChangeBlockersPreventApproval() {
        PublishFacts cancelled = new PublishFacts(
                "draft-1", "APPROVED", false, "Caption", "", "entry-1", "ACTIVE",
                LocalDate.of(2027, 1, 9), LocalTime.of(22, 0), "venue-1", "handle",
                "party-1", "media-1", true, true, true, true, true, true, true,
                1, 1, WorkflowGateApprovals.ALL);

        var evaluation = evaluator.evaluate(cancelled);

        assertThat(evaluation.context().draftApproved()).isFalse();
        assertThat(failedKeys(evaluation.gateResults())).contains(
                "EVENT_DATA_VALIDATED", "POSTING_PLAN_CURRENT");
    }

    @Test
    void publishFlagFalseDeniesEvenWhenEveryServerSideGatePassed() {
        var evaluation = evaluator.evaluate(completeFacts());
        PublishDecision decision = new ConfiguredPublishEligibility("false")
                .evaluate(evaluation.context());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("NOIZY_PUBLISH_ENABLED");
    }

    private static java.util.List<String> failedKeys(Map<String, GateResult> results) {
        return results.values().stream().filter(result -> !result.passed())
                .map(result -> Objects.requireNonNull(result).key()).toList();
    }
}
