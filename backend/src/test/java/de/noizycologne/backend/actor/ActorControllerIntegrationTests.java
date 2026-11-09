package de.noizycologne.backend.actor;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.json.JsonValues;
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

@SpringBootTest(properties="noizy.api.trust.secret=isolated-proxy-fixture-key-not-a-production-secret")
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ActorControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActorRepository actorRepository;

    private final @NonNull List<String> createdActorIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestActors() {
        actorRepository.deleteAllById(createdActorIds);
        actorRepository.flush();
    }

    @Test
    void persistsRolesAndCollectiveMembershipsThroughApi() throws Exception {
        String suffix = UUID.randomUUID().toString();
        ActorRequest collectiveRequest = new ActorRequest(
                "NC Test Collective " + suffix,
                "collective",
                Set.of("organizer"),
                "nc_collective",
                Set.of()
        );

        ActorResponse collective = createActor(collectiveRequest);
        createdActorIds.add(collective.id());
        assertThat(collective.id()).matches("[0-9a-f-]{36}");

        ActorRequest personRequest = new ActorRequest(
                "NC Test Person " + suffix,
                "person",
                Set.of("dj", "organizer"),
                "nc_person",
                Set.of(collective.id())
        );
        ActorResponse person = createActor(personRequest);
        createdActorIds.add(person.id());
        assertThat(person.roles()).containsExactlyInAnyOrder("dj", "organizer");
        assertThat(person.collectiveIds()).containsExactly(collective.id());

        ActorResponse[] getResponse = getActors();
        assertThat(getResponse).extracting(response -> response.id()).contains(person.id(), collective.id());

        ActorRequest updateRequest = new ActorRequest(
                person.name() + " Updated",
                "person",
                Set.of("dj"),
                "nc_person_updated",
                Set.of(collective.id())
        );
        ActorResponse updated = JsonValues.readRequired(objectMapper,
                mockMvc.perform(requireNonNull(put("/api/v1/actors/{id}", person.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JsonValues.write(objectMapper,updateRequest))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                ActorResponse.class
        );
        assertThat(updated.roles()).containsExactly("dj");
        assertThat(updated.collectiveIds()).containsExactly(collective.id());
        assertThat(updated.instagram()).isEqualTo("nc_person_updated");
        assertThat(actorRepository.findById(person.id())).get()
                .extracting(actor -> actor.getInstagram()).isEqualTo("nc_person_updated");
    }

    @Test
    void rejectsUnknownOrInvalidMembershipReferences() throws Exception {
        ActorRequest unknownCollective = new ActorRequest(
                "NC Invalid Person " + UUID.randomUUID(),
                "person",
                Set.of("dj"),
                "",
                Set.of("missing-collective")
        );

        mockMvc.perform(requireNonNull(post("/api/v1/actors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,unknownCollective))))
                .andExpect(status().isBadRequest());

        ActorRequest collectiveWithMembership = new ActorRequest(
                "NC Invalid Collective " + UUID.randomUUID(),
                "collective",
                Set.of(),
                "",
                Set.of("missing-collective")
        );
        mockMvc.perform(requireNonNull(post("/api/v1/actors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,collectiveWithMembership))))
                .andExpect(status().isBadRequest());
    }

    private ActorResponse createActor(ActorRequest request) throws Exception {
        String body = mockMvc.perform(requireNonNull(post("/api/v1/actors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, ActorResponse.class);
    }

    private ActorResponse[] getActors() throws Exception {
        String body = mockMvc.perform(get("/api/v1/actors"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, ActorResponse[].class);
    }
}
