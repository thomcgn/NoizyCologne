package de.noizycologne.backend.party;

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

    private final List<String> createdPartyIds = new ArrayList<>();
    private final List<String> createdActorIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestData() {
        partySeriesRepository.deleteAllById(createdPartyIds);
        partySeriesRepository.flush();
        actorRepository.deleteAllById(createdActorIds);
        actorRepository.flush();
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
        assertThat(created.recurrence()).isEqualTo(new PartyRecurrenceResponse("weekly", 5, null, null));

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
        PartySeriesResponse updated = objectMapper.readValue(
                mockMvc.perform(put("/api/v1/parties/{id}", created.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                PartySeriesResponse.class
        );
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.color()).isEqualTo("#D4E5F6");
        assertThat(updated.residentDjIds()).containsExactly(otherDj.getId());
        assertThat(updated.otherDjIds()).isEmpty();
        assertThat(updated.recurrence()).isEqualTo(new PartyRecurrenceResponse("monthly_weekday", 2, -1, null));
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
        assertThat(mockMvc.perform(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
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
                        ))))
                .andExpect(status().isBadRequest())
                .andReturn()).isNotNull();
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);

        mockMvc.perform(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
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
                        ))))
                .andExpect(status().isBadRequest());
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);

        mockMvc.perform(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
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
                        ))))
                .andExpect(status().isBadRequest());
        assertThat(partySeriesRepository.count()).isEqualTo(partyCount);
    }

    private Actor createActor(String name, String kind, Set<String> roles) {
        Actor actor = actorRepository.saveAndFlush(new Actor(name, kind, "", roles, Set.of()));
        createdActorIds.add(actor.getId());
        return actor;
    }

    private PartySeriesResponse createParty(PartySeriesRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/v1/parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, PartySeriesResponse.class);
    }

    private PartySeriesResponse[] getParties() throws Exception {
        String body = mockMvc.perform(get("/api/v1/parties"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, PartySeriesResponse[].class);
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
        return new PartySeriesRequest(
                name,
                instagram,
                color,
                "residents",
                residentDjIds,
                otherDjIds,
                organizerIds,
                recurrence
        );
    }
}
