package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CologneGeographyTests {
    private final CologneGeography filter=new CologneGeography();
    private EventExtractor.@NonNull Event event(String title,String name,EventPlace place){return new EventExtractor.Event("event",title,"https://portal.example/koeln/events/party",List.of("2027-01-09"),"Party in Köln erwähnt, aber kein Ortsbeleg","",name,place);}
    private EventPlace place(String city){return new EventPlace(city,"DE","Echte Straße 12","","",null,null,false);}
    @Test void cologneVenuesAndAllOtherExplicitCitiesAreClassifiedBeforeAnyPartyMatching() {
        for(String name:List.of("Blue Shell","MTC","Sonic Ballroom")) assertThat(filter.evaluate(event("Party",name,place("Köln")),List.of()).status()).isEqualTo("INSIDE");
        for(String city:List.of("Bonn","Aachen","Berlin","Frankfurt am Main")) assertThat(filter.evaluate(event("Köln Party","Gleis8",place(city)),List.of()).status()).isEqualTo("OUTSIDE");
    }
    @Test void neitherTitleSourceUrlNorUnscopedTextOrNameEqualityProvesCologne() {
        var nameOnly=event("Köln Party","Blue Shell",EventPlace.empty());
        var known=new CologneGeography.ConfirmedVenue("blue","Köln",Set.of("blueshell"),Set.of("ref:https://venue.example/"));
        assertThat(filter.evaluate(nameOnly,List.of(known)).status()).isEqualTo("UNRESOLVED");
        assertThat(filter.evaluate(event("Party","Unbekannt",EventPlace.empty()),List.of()).status()).isEqualTo("UNRESOLVED");
    }
    @Test void confirmedPlaceIdentityReusesNormalizedNamesAndConfirmedAliasesButNotNamesakes() {
        var evidence=new EventPlace("","","","","https://venue.example/",null,null,false);
        var known=new CologneGeography.ConfirmedVenue("blue","Köln",Set.of("blueshell","blauerclub"),Set.of("ref:https://venue.example/"));
        assertThat(filter.evaluate(event("Party","BLUE-SHELL",evidence),List.of(known)).venueId()).isEqualTo("blue");
        assertThat(filter.evaluate(event("Party","Blauer Club",evidence),List.of(known)).status()).isEqualTo("INSIDE");
        assertThat(filter.evaluate(event("Party","BLUE SHELL",place("Bonn")),List.of(known)).status()).isEqualTo("OUTSIDE");
    }
    @Test void multipleConfirmedVenuesForTheSameNameAndAddressRequireManualClarification() {
        var place=place("Köln");
        Set<@NonNull String> names=Set.of("blueshell");
        var keys=CologneGeography.keys(place);
        var result=filter.evaluate(event("Bekannte Party","Blue Shell",place),List.of(
            new CologneGeography.ConfirmedVenue("first","Köln",names,keys),
            new CologneGeography.ConfirmedVenue("second","Köln",names,keys)));
        assertThat(result.status()).isEqualTo("UNRESOLVED");
        assertThat(result.venueId()).isNull();
    }
    @Test void preciseCoordinatesUseTheActualCityPolygonAndNeverARadiusOrBoundingBox() {
        assertThat(point(50.936504,6.939821)).isEqualTo("INSIDE");
        assertThat(point(50.737431,7.098207)).isEqualTo("OUTSIDE");
        assertThat(point(50.914581,6.812592)).isEqualTo("OUTSIDE"); // Frechen, inside the city's bounding box
        assertThat(point(52.520008,13.404954)).isEqualTo("OUTSIDE");
        assertThat(point(50.9,6.9)).isEqualTo("UNRESOLVED");
    }
    private String point(double latitude,double longitude){return filter.evaluate(event("Party","Ort",new EventPlace("","","","","",latitude,longitude,false)),List.of()).status();}
    @Test void conflictingCoordinatesAndCityRemainUnresolved() {
        assertThat(filter.evaluate(event("Party","Ort",new EventPlace("Köln","DE","","","",50.737431,7.098207,false)),List.of()).status()).isEqualTo("UNRESOLVED");
    }
    @Test void structuredLocationIsRetainedAndACologneAftershowSurvivesAnOutOfTownConcert() {
        String html="""
            <h1>Konzert</h1><script type='application/ld+json'>{"@type":"Event","name":"Konzert","location":{"name":"Gleis8","address":{"addressLocality":"Bonn"}},"subEvent":{"@type":"Event","name":"Aftershow Party","startDate":"2027-01-09","location":{"name":"Blue Shell","url":"https://blue.example/","address":{"addressLocality":"Köln","streetAddress":"Luxemburger Straße 32","addressCountry":"DE"}}}}</script>
            """;
        var extraction=new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://portal.example/event"),html,"text/html"),EventExtractor.Profile.defaults());
        assertThat(extraction.events()).hasSize(2);
        var concert=Objects.requireNonNull(extraction.events().getFirst());
        assertThat(PartyOnlyPolicy.concert(concert)).isTrue();
        assertThat(filter.evaluate(concert,List.of()).status()).isEqualTo("OUTSIDE");
        var party=Objects.requireNonNull(extraction.events().getLast());assertThat(party.title()).isEqualTo("Aftershow Party");
        assertThat(PartyOnlyPolicy.concert(party)).isFalse();
        assertThat(filter.evaluate(party,List.of()).status()).isEqualTo("INSIDE");
        assertThat(EventPrivacy.safe(party).place().city()).isEqualTo("Köln");
    }
    @Test void canonicalUrlsStripOnlyTheTransientTransportParameterOnEveryHost() {
        assertThat(CanonicalSourceUrl.of("https://qrushapp.de/eventfinder/koeln?_rsc=abc&category=party")).isEqualTo("https://qrushapp.de/eventfinder/koeln?category=party");
        assertThat(CanonicalSourceUrl.of("https://other.example/events?_rsc=abc")).isEqualTo("https://other.example/events");
    }
    @Test void addressStringsAndNumericPlaceCoordinatesAreScopedEvidenceButOrganizerAddressesAreNot() {
        String html="""
          <script type='application/ld+json'>[{"@type":"Event","name":"Indie Party","location":{"name":"Ort","geo":{"latitude":50.936504,"longitude":6.939821}}},{"@type":"Event","name":"Andere Party","location":{"name":"Blue Shell","address":"Luxemburger Straße 32, 50674 Köln"}}]</script>
          """;
        var extraction=new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://portal.example/event"),html,"text/html"),EventExtractor.Profile.defaults());
        assertThat(extraction.events()).hasSize(2);
        for(var item:extraction.events()) assertThat(filter.evaluate(item,List.of()).status()).isEqualTo("INSIDE");
        String organizer="<article><h2>Indie Party</h2><time datetime='2027-01-09'>Samstag</time><div itemprop='organizer'><span itemprop='addressLocality'>Köln</span></div></article>";
        var unknown=new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://portal.example/event"),organizer,"text/html"),EventExtractor.Profile.defaults());
        assertThat(filter.evaluate(Objects.requireNonNull(unknown.events().getFirst()),List.of()).status()).isEqualTo("UNRESOLVED");
    }

}
