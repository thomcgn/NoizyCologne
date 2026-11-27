package de.noizycologne.backend.instagram.gates;

/**
 * Serverseitige, konfigurative Freigaben für Gates, für die es im aktuellen
 * Datenmodell noch kein belastbares Fachdatum gibt. Alle Werte sind standardmäßig
 * {@code false}; insbesondere bleibt die angekündigte zusätzliche Workflowstufe
 * blockiert, bis sie ausdrücklich spezifiziert und freigegeben wurde.
 */
public record WorkflowGateApprovals(
        boolean postOrderSelected,
        boolean weekendDayBalanceReviewed,
        boolean postingPlanCurrent,
        boolean handlesReviewed,
        boolean captionStaleCheck,
        boolean additionalWorkflowDefined) {

    public static final WorkflowGateApprovals NONE =
            new WorkflowGateApprovals(false, false, false, false, false, false);

    public static final WorkflowGateApprovals ALL =
            new WorkflowGateApprovals(true, true, true, true, true, true);
}
