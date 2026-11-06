package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Objects.requireNonNull;

class DiscoveryBatchMergerTests {
    private DiscoveryBatchMerger.@NonNull Input input(String source,String id,String date,String text,String location,boolean official) {
        return new DiscoveryBatchMerger.Input(source,new EventExtractor.Event(id,"Neon Night","https://"+source+".example/event",List.of(requireNonNull(date)),text,"",location),true,official);
    }
    @Test void combinesSameDateAndVenueWithGenreAndTimeWithoutCountingAnAliasAsAnotherEvent() {
        var first=input("venue","id-a","2026-11-07","Party","MTC",true);
        var second=input("ticket","id-b","2026-11-07T23:00","Indie, Emo","MTC Cologne",false);
        var result=DiscoveryBatchMerger.merge(List.of(first,second),"MTC");
        assertThat(result).hasSize(1);var merged=requireNonNull(result.getFirst());
        assertThat(merged.primary().sourceId()).isEqualTo("venue");assertThat(merged.origins()).hasSize(2);
        assertThat(merged.event().evidence()).contains("Indie","Emo","https://venue.example/event","https://ticket.example/event");
        assertThat(merged.event().starts()).containsExactly("2026-11-07T23:00");
    }
    @Test void mismatchedVenueOrUnscopedExternalListingCannotLendGenres() {
        var first=input("venue","id-a","2026-11-07","Party","MTC",true);
        assertThat(DiscoveryBatchMerger.merge(List.of(first,input("ticket","id-b","2026-11-07","Emo","Other Club",false)),"MTC")).hasSize(2);
        assertThat(DiscoveryBatchMerger.merge(List.of(first,input("ticket","id-b","2026-11-07","Emo","",false)),"MTC")).hasSize(2);
    }
    @Test void conflictingDaysAndTimesRemainExplicitlyUnverified() {
        var first=input("venue","same-id","2026-11-07T23:00","Party","MTC",true);
        for (@NonNull String date:List.<@NonNull String>of("2026-11-08T23:00","2026-11-07T22:00")) {
            var merged=requireNonNull(DiscoveryBatchMerger.merge(List.of(first,input("ticket","same-id",date,"Emo","MTC",false)),"MTC").getFirst());
            assertThat(EventVerification.assess(merged.event(),"MTC").status()).isEqualTo("CONFLICTING_DATA");
        }
    }
    @Test void differentStableOccurrencesRemainSeparateAndUnchangedSingletonDoesNotInventDetails() {
        var first=input("venue","id-a","2026-11-07","Party","MTC",true);
        var next=input("venue","id-b","2026-11-14","Party","MTC",true);
        assertThat(DiscoveryBatchMerger.merge(List.of(first,next),"MTC")).hasSize(2);
        assertThat(requireNonNull(DiscoveryBatchMerger.merge(List.of(first),"MTC").getFirst()).event().evidence()).isEqualTo("Party");
    }
}
