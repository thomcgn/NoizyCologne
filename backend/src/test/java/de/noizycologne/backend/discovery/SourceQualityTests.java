package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SourceQualityTests {
    private final URI root=URI.create("https://source.example/");
    @Test void mtcLikePartialRenderingNeverConfirmsAnEmptyCalendar() {
        var source=new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),"BROWSER_PARTIAL",false),"BROWSER",List.of("ROBOTS_BLOCKED","JAVASCRIPT_ERROR"));
        assertThat(SourceQuality.assess(source)).isEqualTo("PARTIAL");
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),"ROBOTS_BLOCKED")))).isEqualTo("BLOCKED");
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),"BROWSER_TIMEOUT")))).isEqualTo("FAILED");
    }
    @Test void onlyExplicitEmptyResultsConfirmNoEventsAndCompleteListsHaveOwnQuality() {
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),"NO_EVENTS",true)))).isEqualTo("NO_EVENTS_CONFIRMED");
        var event=new EventExtractor.Event("id","Emo Night",root.toString(),List.of("2026-11-07"),"Emo","","");
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND",true)))).isEqualTo("SUCCESS");
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND",false)))).isEqualTo("PARTIAL");
        assertThat(SourceQuality.assess(new VenueSourceDiscoveryService.Found(root,new EventExtractor.Extraction(List.of(),"UNINTERPRETABLE")))).isEqualTo("FAILED");
    }
}
