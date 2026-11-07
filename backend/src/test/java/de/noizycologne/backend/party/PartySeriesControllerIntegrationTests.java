package de.noizycologne.backend.party;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.json.JsonValues;
import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class PartySeriesControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private PartySeriesRepository partySeriesRepository;

    @Autowired private de.noizycologne.backend.genre.GenreRepository genreRepository;
    @Autowired private de.noizycologne.backend.venue.VenueRepository planningVenues;
    private de.noizycologne.backend.venue.Venue planningVenue;
    private final @NonNull List<String> createdGenreIds = new ArrayList<>();

    private final @NonNull List<String> createdPartyIds = new ArrayList<>();
    private final @NonNull List<String> createdActorIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestData() {
        partySeriesRepository.deleteAllById(createdPartyIds);
        partySeriesRepository.flush();
        if(planningVenue!=null) planningVenues.deleteById(planningVenue.getId());
        actorRepository.deleteAllById(createdActorIds);
        actorRepository.flush();
        genreRepository.deleteAllById(createdGenreIds);
        genreRepository.flush();
    }

    @Test
    void persistsRelationshipsAndRecurrenceAcrossApiReads() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Actor resident = createActor("NC Party Resident " + suffix, "person", Set.of("dj"));
        Actor otherDj = createActor("NC Party Other DJ " + suffix, "person", Set.of("dj"));
        Actor organizer = createActor("NC Party Organizer " + suffix, "collective", Set.of("organizer"));

        PartySeriesRequest createRequest = request(
                "NC Party " + suffix,
                "nc_party",
                "#A1B2C3",
                Set.of(resident.getId()),
                Set.of(otherDj.getId()),
                Set.of(organizer.getId()),
                "weekly",
                5,
                null,
                null
        );

        PartySeriesResponse created = createParty(createRequest);
        createdPartyIds.add(created.id());
        assertThat(created.id()).matches("[0-9a-f-]{36}");
        assertThat(created.residentDjIds()).containsExactly(resident.getId());
        assertThat(created.otherDjIds()).containsExactly(otherDj.getId());
        assertThat(created.organizerIds()).containsExactly(organizer.getId());
        assertThat(created.recurrence()).isEqualTo(new PartyRecurrenceResponse("weekly", 5, null, null,planningVenue.getId(),java.time.LocalDate.of(2099,1,1)));

        PartySeriesResponse[] afterReload = getParties();
        PartySeriesResponse persisted = List.of(afterReload).stream()
                .filter(party -> party.id().equals(created.id()))
                .findFirst()
                .orElseThrow();
        assertThat(persisted.color()).isEqualTo("#A1B2C3");
        assertThat(persisted.instagram()).isEqualTo("nc_party");
        assertThat(persisted.residentDjIds()).containsExactly(resident.getId());
        assertThat(persisted.otherDjIds()).containsExactly(otherDj.getId());
        assertThat(persisted.organizerIds()).containsExactly(organizer.getId());

        PartySeriesRequest updateRequest = request(
                created.name() + " Updated",
                "nc_party_updated",
                "#D4E5F6",
                Set.of(otherDj.getId()),
                Set.of(),
                Set.of(organizer.getId()),
                "monthly_weekday",
                2,
                -1,
                null
        );
        PartySeriesResponse updated = JsonValues.readRequired(objectMapper,
                mockMvc.perform(requireNonNull(put("/api/v1/parties/{id}", created.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JsonValues.write(objectMapper,updateRequest))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                PartySeriesResponse.class
        );
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.color()).isEqualTo("#D4E5F6");
        assertThat(updated.residentDjIds()).containsExactly(otherDj.getId());
        assertThat(updated.otherDjIds()).isEmpty();
        assertThat(updated.recurrence()).isEqualTo(new PartyRecurrenceResponse("monthly_weekday", 2, -1, null,planningVenue.getId(),java.time.LocalDate.of(2099,1,1)));
    }

    @Test void persistsSeriesDefaultsAndKeepsUnknownSeparateFromFree() throws Exception {
        String name="Default Party "+UUID.randomUUID();
        var created=createParty(new PartySeriesRequest(name,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of(),"22:45",0));
        createdPartyIds.add(created.id());
        assertThat(created.defaultStartTime()).isEqualTo("22:45");assertThat(created.defaultEntryPrice()).isZero();
        var update=new PartySeriesRequest(name,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of(),"23:15",12);
        mockMvc.perform(requireNonNull(put("/api/v1/parties/{id}",created.id()).contentType(MediaType.APPLICATION_JSON).content(JsonValues.write(objectMapper,update)))).andExpect(status().isOk());
        var reloaded=List.of(getParties()).stream().filter(party->party.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(reloaded.defaultStartTime()).isEqualTo("23:15");assertThat(reloaded.defaultEntryPrice()).isEqualTo(12);
        var unknown=new PartySeriesRequest(name,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of(),null,null);
        mockMvc.perform(requireNonNull(put("/api/v1/parties/{id}",created.id()).contentType(MediaType.APPLICATION_JSON).content(JsonValues.write(objectMapper,unknown)))).andExpect(status().isOk());
        var cleared=List.of(getParties()).stream().filter(party->party.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(cleared.defaultStartTime()).isNull();assertThat(cleared.defaultEntryPrice()).isNull();
    }

    @Test void rejectsMissingNewSeriesTimeInvalidTimesAndNonWholeOrOutOfRangePrices() throws Exception {
        for(String fields:List.of("\"defaultEntryPrice\":null", "\"defaultStartTime\":\"24:00\"", "\"defaultStartTime\":\"23:15:30\"", "\"defaultStartTime\":\"23:00\",\"defaultEntryPrice\":-1", "\"defaultStartTime\":\"23:00\",\"defaultEntryPrice\":31", "\"defaultStartTime\":\"23:00\",\"defaultEntryPrice\":8.5")) {
            String body="{\"name\":\"Invalid "+UUID.randomUUID()+"\",\"color\":\"#123456\",\"djMentionDefault\":\"manual\","+fields+"}";
            mockMvc.perform(requireNonNull(post("/api/v1/parties").contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void persistsOtherRecurrenceAndRejectsInvalidReferencesOrAssignments() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Actor dj = createActor("NC Invalid Party DJ " + suffix, "person", Set.of("dj"));
        Actor organizer = createActor("NC Invalid Party Organizer " + suffix, "person", Set.of("organizer"));

        PartySeriesResponse otherRecurrence = createParty(request(
                "NC Other Recurrence " + suffix,
                "",
                "#123456",
                Set.of(dj.getId()),
                Set.of(),
                Set.of(organizer.getId()),
                "other",
                null,
                null,
                "Unregelmäßig nach Ankündigung"
        ));
        createdPartyIds.add(otherRecurrence.id());
        assertThat(otherRecurrence.recurrence()).isEqualTo(
                new PartyRecurrenceResponse("other", null, null, "Unregelmäßig nach Ankündigung")
        );

        long partyCount = partySeriesRepository.count();
        assertThat(mockMvc.perform(requireNonNull(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "NC Unknown Actor Party " + suffix,
                                "",
                                "#123456",
                                Set.of("missing-actor"),
                                Set.of(),
                                Set.of(),
                                null,
                                null,
                                null,
                                null
                        )))))
                .andExpect(status().isBadRequest())
                .andReturn()).isNotNull();
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);

        mockMvc.perform(requireNonNull(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "NC Wrong Role Party " + suffix,
                                "",
                                "#123456",
                                Set.of(),
                                Set.of(),
                                Set.of(dj.getId()),
                                null,
                                null,
                                null,
                                null
                        )))))
                .andExpect(status().isBadRequest());
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);

        mockMvc.perform(requireNonNull(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "NC Duplicate DJ Party " + suffix,
                                "",
                                "#123456",
                                Set.of(dj.getId()),
                                Set.of(dj.getId()),
                                Set.of(),
                                null,
                                null,
                                null,
                                null
                        )))))
                .andExpect(status().isBadRequest());
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);
    }

    @Test
    void savesDisplayNamesAndSupportsReloadAddRemoveAndDuplicateValues() throws Exception {
        String name="Genre Contract "+UUID.randomUUID();
        var request=new PartySeriesRequest(name,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of("Emo","Metalcore","Hardcore"));
        var created=createParty(request);
        createdPartyIds.add(created.id());
        assertThat(created.standardGenres()).containsExactlyInAnyOrder("Emo","Metalcore","Hardcore");
        var reloaded=List.of(getParties()).stream().filter(p -> p.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(reloaded.standardGenres()).containsExactlyInAnyOrder("Emo","Metalcore","Hardcore");
        String update="""
            {"name":"%s","color":"#123456","djMentionDefault":"manual","standardGenres":["Emo","Emo"," Metalcore ","Punk"]}
            """.formatted(name);
        var updated=JsonValues.readRequired(objectMapper,mockMvc.perform(requireNonNull(put("/api/v1/parties/{id}",created.id())
            .contentType(MediaType.APPLICATION_JSON).content(requireNonNull(update)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),PartySeriesResponse.class);
        assertThat(updated.standardGenres()).containsExactlyInAnyOrder("Emo","Metalcore","Punk");
        assertThat(requireNonNull(partySeriesRepository.findById(created.id()).orElseThrow()).getStandardGenres()).containsExactlyInAnyOrder("Emo","Metalcore","Punk");
    }

    @Test
    void rejectsUnknownInactiveAndAmbiguousDisplayNamesWithoutChangingStoredGenres() throws Exception {
        String suffix=UUID.randomUUID().toString();
        String inactive="Inactive "+suffix, ambiguous="Ambiguous "+suffix;
        for (var genre:List.of(
            new de.noizycologne.backend.genre.Genre("inactive-"+suffix,inactive,false),
            new de.noizycologne.backend.genre.Genre("duplicate-a-"+suffix,ambiguous,true),
            new de.noizycologne.backend.genre.Genre("duplicate-b-"+suffix,ambiguous,true))) {
            createdGenreIds.add(genreRepository.saveAndFlush(genre).getId());
        }
        var created=createParty(new PartySeriesRequest("Invalid Genres "+suffix,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of("Emo")));
        createdPartyIds.add(created.id());
        for (String invalid:List.of("Missing "+suffix,inactive,ambiguous,"emo")) {
            var update=new PartySeriesRequest(created.name(),"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of("Emo",invalid));
            String body=mockMvc.perform(requireNonNull(put("/api/v1/parties/{id}",created.id()).contentType(MediaType.APPLICATION_JSON)
                .content(JsonValues.write(objectMapper,update)))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(body).get("message").asString()).contains(invalid);
            assertThat(requireNonNull(partySeriesRepository.findById(created.id()).orElseThrow()).getStandardGenres()).containsExactly("Emo");
        }
        // Historical inactive names are readable; saving them again requires an explicit removal.
        var historical=requireNonNull(partySeriesRepository.findById(created.id()).orElseThrow());
        historical.getStandardGenres().add(inactive);partySeriesRepository.saveAndFlush(historical);
        assertThat(List.of(getParties()).stream().filter(p -> p.id().equals(created.id())).findFirst().orElseThrow().standardGenres())
            .containsExactlyInAnyOrder("Emo",inactive);
    }

    @Test
    void inactiveDuplicateDoesNotMakeAnActiveDisplayNameAmbiguous() throws Exception {
        String suffix=UUID.randomUUID().toString(), display="Unique Active "+suffix;
        for (boolean active:List.of(true,false)) {
            var genre=new de.noizycologne.backend.genre.Genre("active-"+active+"-"+suffix,display,active);
            createdGenreIds.add(genreRepository.saveAndFlush(genre).getId());
        }
        var created=createParty(new PartySeriesRequest("Active Genre "+suffix,"","#123456","manual",Set.of(),Set.of(),Set.of(),null,Set.of(display)));
        createdPartyIds.add(created.id());
        assertThat(created.standardGenres()).containsExactly(display);
    }

    private Actor createActor(String name, String kind, Set<String> roles) {
        Actor actor = actorRepository.saveAndFlush(new Actor(name, kind, "", roles, Set.of()));
        createdActorIds.add(actor.getId());
        return actor;
    }

    private PartySeriesResponse createParty(PartySeriesRequest request) throws Exception {
        if (request.defaultStartTime() == null) request = new PartySeriesRequest(request.name(), request.instagram(), request.color(), request.djMentionDefault(), request.residentDjIds(), request.otherDjIds(), request.organizerIds(), request.recurrence(), request.standardGenres(), "23:00", request.defaultEntryPrice());
        String body = mockMvc.perform(requireNonNull(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, PartySeriesResponse.class);
    }

    private PartySeriesResponse[] getParties() throws Exception {
        String body = mockMvc.perform(get("/api/v1/parties"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, PartySeriesResponse[].class);
    }

    private PartySeriesRequest request(
            String name,
            String instagram,
            String color,
            Set<String> residentDjIds,
            Set<String> otherDjIds,
            Set<String> organizerIds,
            String recurrenceKind,
            Integer weekday,
            Integer occurrence,
            String description
    ) {
        PartyRecurrenceRequest recurrence = recurrenceKind == null
                ? null
                : new PartyRecurrenceRequest(recurrenceKind, weekday, occurrence, description);
        if ("weekly".equals(recurrenceKind) || "monthly_weekday".equals(recurrenceKind)) {
            if(planningVenue==null) planningVenue=planningVenues.saveAndFlush(new de.noizycologne.backend.venue.Venue("Bestätigte Turnus-Location", ""));
            recurrence=new PartyRecurrenceRequest(recurrenceKind,weekday,occurrence,description,planningVenue.getId(),java.time.LocalDate.of(2099,1,1));
        }
        return new PartySeriesRequest(
                name,
                instagram,
                color,
                "residents",
                residentDjIds,
                otherDjIds,
                organizerIds,
                recurrence,
                Set.of(), "23:00", null
        );
    }
}
