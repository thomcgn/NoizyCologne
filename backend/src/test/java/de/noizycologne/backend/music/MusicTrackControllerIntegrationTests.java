package de.noizycologne.backend.music;

import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.editorial.EditorialPostDraftRepository;
import de.noizycologne.backend.editorial.EditorialPostDraftRequest;
import de.noizycologne.backend.editorial.EditorialPostDraftResponse;
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
class MusicTrackControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MusicTrackRepository trackRepository;
    @Autowired private EditorialPostDraftRepository draftRepository;
    @Autowired private CalendarEntryRepository entryRepository;
    @Autowired private PartySeriesRepository partyRepository;
    @Autowired private VenueRepository venueRepository;

    private final List<String> trackIds = new ArrayList<>();
    private final List<String> draftIds = new ArrayList<>();
    private final List<String> entryIds = new ArrayList<>();
    private final List<String> partyIds = new ArrayList<>();
    private final List<String> venueIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        draftRepository.deleteAllById(draftIds);
        draftRepository.flush();
        entryRepository.deleteAllById(entryIds);
        entryRepository.flush();
        trackRepository.deleteAllById(trackIds);
        trackRepository.flush();
        partyRepository.deleteAllById(partyIds);
        partyRepository.flush();
        venueRepository.deleteAllById(venueIds);
        venueRepository.flush();
    }

    @Test
    void persistsVerifiedTrackRanksPartyFavoriteAndLinksDraft() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = venueRepository.saveAndFlush(new Venue("Music Venue " + suffix, "music_venue"));
        venueIds.add(venue.getId());
        PartySeries party = partyRepository.saveAndFlush(new PartySeries(
                "Music Party " + suffix, "music_party", "#123456", "manual", null,
                null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());
        CalendarEntry entry = entryRepository.saveAndFlush(new CalendarEntry(
                LocalDate.of(2027, 5, 20), venue.getId(), "event", "", party.getId(),
                LocalTime.of(21, 0), null, Set.of()));
        entryIds.add(entry.getId());

        MusicTrackRequest request = new MusicTrackRequest(
                "Verified Artist", "Verified Song", Set.of("Punk"), Set.of(party.getId()),
                "dQw4w9WgXcQ", "", 12, 12);
        MusicTrackResponse created = objectMapper.readValue(
                mockMvc.perform(post("/api/v1/music/tracks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                MusicTrackResponse.class);
        trackIds.add(created.id());
        assertThat(created.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");

        MusicTrackResponse[] suggestions = objectMapper.readValue(
                mockMvc.perform(get("/api/v1/music/tracks").param("calendarEntryId", entry.getId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                MusicTrackResponse[].class);
        assertThat(List.of(suggestions)).extracting(MusicTrackResponse::id).contains(created.id());
        assertThat(List.of(suggestions).stream().filter(item -> item.id().equals(created.id())).findFirst().orElseThrow().matchReasons())
                .contains("Partyreihen-Favorit");

        EditorialPostDraftResponse draft = objectMapper.readValue(
                mockMvc.perform(post("/api/v1/calendar-entries/{id}/editorial-drafts", entry.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new EditorialPostDraftRequest(
                                        "Caption", "#test", "DRAFT", Set.of(), Set.of("Punk"), created.id()))))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                EditorialPostDraftResponse.class);
        draftIds.add(draft.id());
        assertThat(draft.musicTrackId()).isEqualTo(created.id());
        assertThat(trackRepository.findById(created.id()).orElseThrow().getLastUsedAt()).isNotNull();

        MusicTrackResponse updated = objectMapper.readValue(
                mockMvc.perform(put("/api/v1/music/tracks/{id}", created.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new MusicTrackRequest(
                                        request.artist(), request.title(), request.genres(), request.partySeriesIds(),
                                        request.youtubeVideoId(), request.spotifyTrackUrl(), request.previewStartSeconds(), 42))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                MusicTrackResponse.class);
        assertThat(updated.preferredPositionSeconds()).isEqualTo(42);
    }
}
