package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryAssessmentTests {
    private @NonNull PartySeries party(String id,String name) {
        var party=mock(PartySeries.class);when(party.getId()).thenReturn(id);when(party.getName()).thenReturn(name);
        when(party.getStandardGenres()).thenReturn(Set.of("Indie"));return party;
    }
    private EventExtractor.Event event(String title,List<@NonNull String> starts,String state) {
        return new EventExtractor.Event("neon-event",title,"https://club.example/events/neon",starts,"Indie / Emo DJs",state,"Blue Shell");
    }
    @Test void blueShellCooperationKeepsBothSeriesWithoutAnArbitraryPrimaryParty() {
        var parties=List.of(party("paradise","NEON PARADISE"),party("kult","NEON KULT"));
        var event=event("NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE",List.of("2026-11-07T23:00"),"UNCONFIRMED");
        var recognition=PartySeriesRecognition.recognize(event.title(),parties,Map.of());
        assertThat(recognition.status()).isEqualTo("COOPERATION");
        assertThat(recognition.parties()).extracting(PartySeriesRecognition.DetectedParty::id).containsExactly("paradise","kult");
        var match=new EventMatcher().match(event,"Blue Shell",parties,Map.of(),List.of("Emo"),true);
        assertThat(match.status()).isEqualTo("NEEDS_REVIEW");assertThat(match.partyId()).isNull();
        assertThat(EventVerification.assess(event,"Blue Shell").date()).isEqualTo(LocalDate.of(2026,11,7));
        assertThat(EventLifecycle.signal(event)).isEqualTo("ACTIVE");
    }
    @Test void longerTitlesNormalizePunctuationUnicodeAndExplicitAliasesWithoutFuzzyMatching() {
        var parties=List.of(party("paradise","NEON PARADISE"));
        for(@NonNull String title:List.<@NonNull String>of("Heute: NEON—PARADISE! Halloween Special", "Blue Shell presents NEON PARADISE Vol. 2 – The Revenge", "Clubnacht: NＰ Night – Edition 2")) {
            var match=new EventMatcher().match(event(title,List.of("2026-11-07"),""),"Blue Shell",parties,
                Map.of("paradise",List.of("NP Night")),List.of(),true);
            org.junit.jupiter.api.Assertions.assertEquals("READY",match.status(),title);assertThat(match.partyId()).isEqualTo("paradise");
        }
        for(@NonNull String title:List.<@NonNull String>of("NEON PARADISEland", "XNEON PARADISE", "NEON PARADISES", "NEON PARADIS"))
            org.junit.jupiter.api.Assertions.assertEquals("NONE",PartySeriesRecognition.recognize(title,parties,Map.of()).status(),title);
    }
    @Test void cooperationConnectorsAndAmbiguousAliasesNeverSelectOneSeries() {
        var parties=List.of(party("paradise","NEON PARADISE"),party("kult","NEON KULT"));
        for(@NonNull String connector:List.<@NonNull String>of("vs.","meets","presents"))
            assertThat(PartySeriesRecognition.recognize("NEON PARADISE "+connector+" NEON KULT Special",parties,Map.of()).status()).isEqualTo("COOPERATION");
        assertThat(PartySeriesRecognition.recognize("NEON PARADISE oder NEON KULT",parties,Map.of()).status()).isEqualTo("AMBIGUOUS");
        var shared=PartySeriesRecognition.recognize("Tonight Shared Alias",parties,Map.of("paradise",List.of("Shared Alias"),"kult",List.of("Shared Alias")));
        assertThat(shared.status()).isEqualTo("AMBIGUOUS");assertThat(shared.parties()).hasSize(2);
    }
    @Test void benignConfirmationMetadataDoesNotOverrideCompleteEvidenceButSpeculationDoes() {
        var parties=List.of(party("paradise","NEON PARADISE"));
        for(String marker:List.of("", "UNCONFIRMED", "UNCONFIRMED Detailseite nicht eindeutig auswertbar"))
            assertThat(new EventMatcher().match(event("NEON PARADISE",List.of("2026-11-07"),marker),"Blue Shell",parties,Map.of(),List.of(),true).status()).isEqualTo("READY");
        for(String marker:List.of("TENTATIVE", "SPECULATIVE_DATE", "UNCONFIRMED Rohdaten unvollständig"))
            assertThat(new EventMatcher().match(event("NEON PARADISE",List.of("2026-11-07"),marker),"Blue Shell",parties,Map.of(),List.of(),true).status()).isEqualTo("NEEDS_INQUIRY");
    }
    @Test void actualMissingConflictingAndMalformedDatesRemainIneligible() {
        for(List<@NonNull String> starts:List.of(List.<@NonNull String>of(),List.<@NonNull String>of("2026-11"),
                List.<@NonNull String>of("2026-11-07","2026-11-08"),List.<@NonNull String>of("2026-11-07","not-a-date")))
            assertThat(EventVerification.assess(event("NEON PARADISE",starts,"UNCONFIRMED"),"Blue Shell").sufficient()).isFalse();
        assertThat(EventVerification.assess(event("",List.of("2026-11-07"),""),"Blue Shell").sufficient()).isFalse();
        assertThat(EventVerification.assess(event("NEON PARADISE",List.of("2026-11-07T22:00","2026-11-07T23:00"),""),"Blue Shell").conflicting()).isTrue();
    }
    @Test void unknownGenreMatchRemainsAProposalAndUnapprovedSourceRequiresReview() {
        assertThat(new EventMatcher().match(event("New Night",List.of("2026-11-07"),""),"Blue Shell",List.of(),Map.of(),List.of("Emo"),true).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(new EventMatcher().match(event("NEON PARADISE",List.of("2026-11-07"),""),"Blue Shell",List.of(party("paradise","NEON PARADISE")),Map.of(),List.of(),false).status()).isEqualTo("NEEDS_REVIEW");
    }
}
