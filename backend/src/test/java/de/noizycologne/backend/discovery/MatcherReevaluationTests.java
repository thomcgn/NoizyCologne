package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatcherReevaluationTests {
    @Test void localFixturesCoverMissingGenresNamesAliasesSpecialsAndSeparateAftershow() throws Exception {
        var party=mock(PartySeries.class);when(party.getId()).thenReturn("known");when(party.getName()).thenReturn("Emo Tion");
        when(party.getStandardGenres()).thenReturn(Set.of("Emo","Metalcore"));
        var fixtures=JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/discovery/reevaluation.json"));
        for (var fixture:fixtures) {
            var event=new EventExtractor.Event("id",fixture.get("title").asString(),"https://club.example/party",List.of("2027-01-09"),fixture.get("evidence").asString(),"","");
            var result=new EventMatcher().match(event,"Club",List.of(party),Map.of("known",List.of("Explicit Alias")),List.of("Emo","Metalcore"),true);
            org.junit.jupiter.api.Assertions.assertEquals(fixture.get("status").asString(),result.status(),event.title());
            org.junit.jupiter.api.Assertions.assertEquals(fixture.get("classification").asString(),result.classification(),event.title());
        }
    }
    @Test void knownPartyWithConflictingDatesIsNeverReady() {
        var party=mock(PartySeries.class);when(party.getName()).thenReturn("Emo Tion");when(party.getId()).thenReturn("known");
        var event=new EventExtractor.Event("id","Emo Tion","https://club.example/party",List.of("2027-01-09","2027-01-10"),"","","");
        var result=new EventMatcher().match(event,"Club",List.of(party),Map.of(),List.of(),true);
        assertThat(result.classification()).isEqualTo("MATCH");assertThat(result.status()).isEqualTo("NEEDS_INQUIRY");
    }
    @Test void ambiguousNamesAndUnrelatedSubstringsNeverAutoImport() {
        var a=mock(PartySeries.class);when(a.getName()).thenReturn("Night");when(a.getId()).thenReturn("a");
        var b=mock(PartySeries.class);when(b.getName()).thenReturn("Night");when(b.getId()).thenReturn("b");
        var event=new EventExtractor.Event("id","Night","",List.of("2027-01-09"),"","","");
        assertThat(new EventMatcher().match(event,"Club",List.of(a,b),Map.of(),List.of(),true).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(EventMatcher.nameMatches("Nightmare","Night")).isFalse();
    }
}
