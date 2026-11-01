package de.noizycologne.backend.editorial;

import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import de.noizycologne.backend.venue.Venue;
import de.noizycologne.backend.venue.VenueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
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
class EditorialPostDraftControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EditorialPostDraftRepository draftRepository;
    @Autowired private CalendarEntryRepository entryRepository;
    @Autowired private PartySeriesRepository partyRepository;
    @Autowired private VenueRepository venueRepository;
    @Autowired private ActorRepository actorRepository;

    private final List<String> draftIds = new ArrayList<>();
    private final List<String> entryIds = new ArrayList<>();
    private final List<String> partyIds = new ArrayList<>();
    private final List<String> venueIds = new ArrayList<>();
    private final List<String> actorIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        draftRepository.deleteAllById(draftIds);
        draftRepository.flush();
        entryRepository.deleteAllById(entryIds);
        entryRepository.flush();
        partyRepository.deleteAllById(partyIds);
        partyRepository.flush();
        venueRepository.deleteAllById(venueIds);
        venueRepository.flush();
        actorRepository.deleteAllById(actorIds);
        actorRepository.flush();
    }

    @Test
    void persistsDraftAcrossReadsAndAllowsEveryTeamMemberStatus() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Actor dj = actorRepository.saveAndFlush(new Actor("Draft DJ " + suffix, "person", "draft_dj", Set.of("dj"), Set.of()));
        actorIds.add(dj.getId());
        Venue venue = venueRepository.saveAndFlush(new Venue("Draft Venue " + suffix, "draft_venue"));
        venueIds.add(venue.getId());
        PartySeries party = partyRepository.saveAndFlush(new PartySeries(
                "Draft Party " + suffix, "draft_party", "#123456", "manual", null,
                null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());
        CalendarEntry entry = entryRepository.saveAndFlush(new CalendarEntry(
                LocalDate.of(2027, 4, 12), venue.getId(), "event", "", party.getId(),
                LocalTime.of(22, 0), null, Set.of(dj.getId())));
        entryIds.add(entry.getId());

        EditorialPostDraftRequest createRequest = new EditorialPostDraftRequest(
                "Caption für das Team", "#noizycologne", "DRAFT", Set.of(dj.getId()), Set.of("House", "Disco"));
        EditorialPostDraftResponse created = objectMapper.readValue(
                mockMvc.perform(post("/api/v1/calendar-entries/{id}/editorial-drafts", entry.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                EditorialPostDraftResponse.class);
        draftIds.add(created.id());
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.mentionActorIds()).containsExactly(dj.getId());
        assertThat(created.genres()).containsExactlyInAnyOrder("House", "Disco");

        EditorialPostDraftResponse[] reloaded = objectMapper.readValue(
                mockMvc.perform(get("/api/v1/editorial-drafts"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                EditorialPostDraftResponse[].class);
        assertThat(List.of(reloaded)).extracting(response -> response.id()).contains(created.id());

        EditorialPostDraftRequest updateRequest = new EditorialPostDraftRequest(
                "Überarbeitete Caption", "#team", "READY_FOR_REVIEW", Set.of(), Set.of("Techno"));
        EditorialPostDraftResponse updated = objectMapper.readValue(
                mockMvc.perform(put("/api/v1/editorial-drafts/{id}", created.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                EditorialPostDraftResponse.class);
        assertThat(updated.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(updated.caption()).isEqualTo("Überarbeitete Caption");
        assertThat(updated.genres()).containsExactly("Techno");
    }

    @Test
    void rejectsInvalidMentionAndApprovalWithoutFlyer() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Actor selectedDj = actorRepository.saveAndFlush(new Actor("Selected DJ " + suffix, "person", "selected_dj", Set.of("dj"), Set.of()));
        Actor unrelatedDj = actorRepository.saveAndFlush(new Actor("Unrelated DJ " + suffix, "person", "unrelated_dj", Set.of("dj"), Set.of()));
        actorIds.add(selectedDj.getId());
        actorIds.add(unrelatedDj.getId());
        Venue venue = venueRepository.saveAndFlush(new Venue("Invalid Draft Venue " + suffix, "invalid_venue"));
        venueIds.add(venue.getId());
        PartySeries party = partyRepository.saveAndFlush(new PartySeries(
                "Invalid Draft Party " + suffix, "invalid_party", "#654321", "manual", null,
                null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());
        CalendarEntry entry = entryRepository.saveAndFlush(new CalendarEntry(
                LocalDate.of(2027, 4, 13), venue.getId(), "event", "", party.getId(),
                LocalTime.of(20, 0), null, Set.of(selectedDj.getId())));
        entryIds.add(entry.getId());

        EditorialPostDraftRequest invalidMention = new EditorialPostDraftRequest(
                "Caption", "", "DRAFT", Set.of(unrelatedDj.getId()), Set.of());
        mockMvc.perform(post("/api/v1/calendar-entries/{id}/editorial-drafts", entry.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidMention)))
                .andExpect(status().isBadRequest());

        EditorialPostDraftRequest approvalWithoutFlyer = new EditorialPostDraftRequest(
                "Caption", "", "APPROVED", Set.of(selectedDj.getId()), Set.of("House"));
        mockMvc.perform(post("/api/v1/calendar-entries/{id}/editorial-drafts", entry.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approvalWithoutFlyer)))
                .andExpect(status().isBadRequest());

        assertThat(draftRepository.findByCalendarEntryId(entry.getId())).isEmpty();
    }
}
