package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Offline replay of an explicitly supplied, read-only export. Never opens a database or website. */
@EnabledIfEnvironmentVariable(named="NOIZY_DISCOVERY_SNAPSHOT",matches=".+")
class ReadOnlyDiscoveryDiagnosisTests {
    @Test void replayExportWithoutChangingAnyApplicationData() throws Exception {
        var json=JsonMapper.builder().build();
        var path=Path.of(System.getenv("NOIZY_DISCOVERY_SNAPSHOT"));
        var snapshot=json.readTree(Files.readString(path));
        // This diagnostic export intentionally has no known parties; do not invent them from titles.
        assertThat(snapshot.get("partyCount").asInt()).isZero();
        Set<String> active=new HashSet<>();Set<String> terms=new TreeSet<>();
        for (var genre:snapshot.get("genres")) if (genre.get("active").asBoolean()) {
            active.add(genre.get("id").asString());terms.add(genre.get("canonical_name").asString());terms.add(genre.get("display_name").asString());
        }
        for (var alias:snapshot.get("genreAliases")) if (active.contains(alias.get("genre_id").asString())) terms.add(alias.get("alias_value").asString());
        List<Map<String,Object>> details=new ArrayList<>();Map<String,Integer> totals=new TreeMap<>();
        for (var row:snapshot.get("candidates")) {
            if (!row.get("status").asString().equals("IGNORED")) continue;
            String date=row.get("event_date").isNull() ? "" : row.get("event_date").asString();
            var event=new EventExtractor.Event("",row.get("title").asString(),row.get("source_url").asString(),date.isBlank() ? List.of() : List.of(date),
                row.get("evidence").asString(),"UNCONFIRMED Rohdaten unvollständig; erneuter Abruf erforderlich","");
            var match=new EventMatcher().match(event,row.get("venue_name").asString(),List.of(),Map.of(),terms,false);
            totals.merge(match.classification(),1,Integer::sum);
            details.add(Map.of("title",event.title(),"oldStatus","IGNORED","newStatus",match.status(),"classification",match.classification(),
                "reason",match.reason(),"genreTerms",DiscoveryGenreFilter.matchingTerms(event,terms)));
        }
        var report=Map.of("ruleVersion",EventMatcher.VERSION,"ignoredBefore",details.size(),"results",totals,"knownPartyCount",0,"details",details);
        Files.writeString(path.resolveSibling(path.getFileName()+".diagnosis.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Read-only discovery replay: "+totals+"; total="+details.size());
    }
}
