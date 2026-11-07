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
    @Test void longPrimaryDescriptionDoesNotConsumeTheSupplementGenreEvidence() {
        var first=input("venue","id-a","2026-11-07","a".repeat(8000),"MTC",true);
        var second=input("ticket","id-b","2026-11-07","Emo, Indie DJs","MTC",false);
        var merged=requireNonNull(DiscoveryBatchMerger.merge(List.of(first,second),"MTC").getFirst());
        assertThat(merged.event().evidence()).contains("Emo, Indie DJs");
        assertThat(requireNonNull(merged.origins().getFirst()).event().evidence()).hasSize(8000);
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
    @Test void sameSourceIdentityKeepsConflictingDaysAndTimesTogetherInEitherOrder() {
        var first=input("venue","same-id","2026-12-04T23:00","Party","MTC",true);
        for (@NonNull String date:List.<@NonNull String>of("2026-12-05T23:00","2026-12-04T22:00")) {
            var second=input("venue","same-id",date,"Party","MTC",true);
            for(var order:List.of(List.of(first,second),List.of(second,first))) {
                var result=DiscoveryBatchMerger.merge(order,"MTC");
                assertThat(result).hasSize(1);
                var merged=requireNonNull(result.getFirst());
                assertThat(merged.origins()).hasSize(2);
                assertThat(merged.event().starts()).containsExactlyInAnyOrder("2026-12-04T23:00",date);
                assertThat(merged.event().state()).contains("SOURCE_CONFLICT");
                assertThat(EventVerification.assess(merged.event(),"MTC").status()).isEqualTo("CONFLICTING_DATA");
            }
        }
    }
    @Test void cancellationAndActiveObservationsRemainAnExplicitConflict() {
        var active=input("venue","same-id","2026-12-04","Party","MTC",true);
        var cancelled=new DiscoveryBatchMerger.Input("venue",new EventExtractor.Event("same-id","Neon Night",
            "https://venue.example/event",List.of("2026-12-04"),"Party","EventCancelled","MTC"),true,true);
        for(var order:List.of(List.of(active,cancelled),List.of(cancelled,active))) {
            var result=DiscoveryBatchMerger.merge(order,"MTC");
            assertThat(result).hasSize(1);
            var event=requireNonNull(result.getFirst()).event();
            assertThat(event.state()).contains("SOURCE_CONFLICT");
            assertThat(EventVerification.assess(event,"MTC").status()).isEqualTo("CONFLICTING_DATA");
            assertThat(EventLifecycle.signal(event)).isEqualTo("CANCELLED");
        }
    }
    @Test void repeatedEditionsDoNotMergeThroughASecondSourceObservation() {
        var first=input("venue","","2026-12-04","Party","MTC",true);
        var second=input("venue","","2026-12-11","Party","MTC",true);
        var ticket=input("ticket","","2026-12-04","Party","MTC",true);
        for(var order:List.of(List.of(first,second,ticket),List.of(first,ticket,second),List.of(ticket,second,first))) {
            var result=DiscoveryBatchMerger.merge(order,"MTC");
            assertThat(result).hasSize(2);
            for(var item:result) assertThat(item.event().starts()).hasSize(1);
        }
    }

}
