package de.noizycologne.backend.discovery;

import java.util.List;

/** Transport success is separate from usable extraction and complete coverage. */
final class SourceQuality {
    private SourceQuality() { }
    static String assess(VenueSourceDiscoveryService.Found found) {
        var extraction=found.extraction();
        String status=extraction.status();
        if (!extraction.events().isEmpty())
            return extraction.complete() && status.equals("EVENTS_FOUND") && found.diagnostics().isEmpty() ? "SUCCESS" : "PARTIAL";
        if (status.equals("NO_EVENTS") && found.diagnostics().isEmpty()) return "NO_EVENTS_CONFIRMED";
        if (status.contains("BLOCKED") || List.of("REDIRECT_REQUIRES_REVIEW","UNSAFE_URL").contains(status)) return "BLOCKED";
        if (List.of("SOURCE_LINKS_FOUND","JAVASCRIPT_REQUIRED","BROWSER_DISABLED","BROWSER_PARTIAL","EXCLUDED_CONCERTS").contains(status)) return "PARTIAL";
        return "FAILED";
    }
}
