package de.noizycologne.backend.instagram.gates;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/** Konfigurationsfreigaben für noch nicht fachlich abgenommene Review-Gates. */
@Configuration
public class InstagramGateConfiguration {
    @Bean
    WorkflowGateApprovals workflowGateApprovals(
            @Value("${noizy.instagram.gates.post-order-selected:false}") boolean postOrderSelected,
            @Value("${noizy.instagram.gates.weekend-day-balance-reviewed:false}") boolean weekendDayBalanceReviewed,
            @Value("${noizy.instagram.gates.posting-plan-current:false}") boolean postingPlanCurrent,
            @Value("${noizy.instagram.gates.handles-reviewed:false}") boolean handlesReviewed,
            @Value("${noizy.instagram.gates.caption-stale-check:false}") boolean captionStaleCheck,
            @Value("${noizy.instagram.gates.additional-workflow-defined:false}") boolean additionalWorkflowDefined) {
        return new WorkflowGateApprovals(postOrderSelected, weekendDayBalanceReviewed,
                postingPlanCurrent, handlesReviewed, captionStaleCheck, additionalWorkflowDefined);
    }
}
