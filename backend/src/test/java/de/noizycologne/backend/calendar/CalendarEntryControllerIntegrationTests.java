package de.noizycologne.backend.calendar;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.json.JsonValues;
import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.party.PartyDjAssignment;
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

import java.util.ArrayList;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class CalendarEntryControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CalendarEntryRepository calendarEntryRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private PartySeriesRepository partySeriesRepository;

    private final @NonNull List<String> entryIds = new ArrayList<>();
    private final @NonNull List<String> partyIds = new ArrayList<>();
    private final @NonNull List<String> venueIds = new ArrayList<>();
    private final @NonNull List<String> actorIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestData() {
        calendarEntryRepository.deleteAllById(entryIds);
        calendarEntryRepository.flush();
        partySeriesRepository.deleteAllById(partyIds);
        partySeriesRepository.flush();
        venueRepository.deleteAllById(venueIds);
        venueRepository.flush();
        actorRepository.deleteAllById(actorIds);
        actorRepository.flush();
    }

    @Test
    void persistsEventWithReferencesMentionsAndMultipleSameDayEntries() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = createVenue("NC Calendar Venue " + suffix);
        Actor dj = createActor("NC Calendar DJ " + suffix, "dj_user");
        PartySeries party = createParty("NC Calendar Party " + suffix, dj);

        CalendarEntryResponse created = createEntry(request(
                "2027-01-08", venue.getId(), "event", "Notiz", party.getId(), "22:30", null,
                Set.of(dj.getId())
        ));
        entryIds.add(created.id());
        assertThat(created.id()).matches("[0-9a-f-]{36}");
        assertThat(created.date()).isEqualTo("2027-01-08");
        assertThat(created.startTime()).isEqualTo("22:30");
        assertThat(created.mentionDjIds()).containsExactly(dj.getId());

        CalendarEntryResponse second = createEntry(request(
                "2027-01-08", venue.getId(), "event", "Zweite Veranstaltung", party.getId(), "23:45", null,
                Set.of()
        ));
        entryIds.add(second.id());

        CalendarEntryResponse[] afterReload = getEntries();
        assertThat(List.of(afterReload))
                .extracting(entry -> entry.id())
                .contains(created.id(), second.id());
        assertThat(List.of(afterReload).stream()
                .filter(entry -> entry.id().equals(created.id()))
                .findFirst().orElseThrow().locationId()).isEqualTo(venue.getId());

        CalendarEntryResponse updated = JsonValues.readRequired(objectMapper,
                mockMvc.perform(requireNonNull(put("/api/v1/calendar-entries/{id}", created.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JsonValues.write(objectMapper,request(
                                        "2027-01-09", venue.getId(), "event", "Geändert", party.getId(), "20:15", null,
                                        Set.of()
                                )))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                CalendarEntryResponse.class
        );
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.date()).isEqualTo("2027-01-09");
        assertThat(updated.mentionDjIds()).isEmpty();

        mockMvc.perform(delete("/api/v1/calendar-entries/{id}", second.id()))
                .andExpect(status().isNoContent());
        entryIds.remove(second.id());
        assertThat(calendarEntryRepository.existsById(second.id())).isFalse();
    }

    @Test void snapshotsDefaultsOnlyAtCreationAndPreservesEventOverridesAndRelativeStarts() throws Exception {
        Venue venue=createVenue("Defaults Venue "+UUID.randomUUID());
        Actor dj=createActor("Defaults DJ "+UUID.randomUUID(),"defaults_dj");
        PartySeries party=createParty("Defaults Party "+UUID.randomUUID(),dj);
        assertThat(party.getDefaultStartTime()).isNull();assertThat(party.getDefaultEntryPrice()).isNull();
        party.setDefaults(LocalTime.of(22,45),0);partySeriesRepository.saveAndFlush(party);
        var inherited=createEntry(new CalendarEntryRequest("2027-01-09",venue.getId(),"event","",party.getId(),null,null,Set.of()));entryIds.add(inherited.id());
        assertThat(inherited.startTime()).isEqualTo("22:45");assertThat(inherited.entryPrice()).isEqualTo("Eintritt frei");
        var explicit=createEntry(new CalendarEntryRequest("2027-01-16",venue.getId(),"event","", "15 €",party.getId(),"23:30",null,Set.of()));entryIds.add(explicit.id());
        assertThat(explicit.startTime()).isEqualTo("23:30");assertThat(explicit.entryPrice()).isEqualTo("15 €");
        var relative=createEntry(new CalendarEntryRequest("2027-01-23",venue.getId(),"event","Beginn: nach dem Konzert",party.getId(),null,null,Set.of()));entryIds.add(relative.id());
        assertThat(relative.startTime()).isNull();
        party.setDefaults(LocalTime.of(21,0),null);partySeriesRepository.saveAndFlush(party);
        assertThat(getEntries()).anySatisfy(item->{assertThat(item.id()).isEqualTo(inherited.id());assertThat(item.startTime()).isEqualTo("22:45");assertThat(item.entryPrice()).isEqualTo("Eintritt frei");});
        var unknown=createEntry(new CalendarEntryRequest("2027-02-06",venue.getId(),"event","",party.getId(),null,null,Set.of()));entryIds.add(unknown.id());
        assertThat(unknown.entryPrice()).isEmpty();
        var update=new CalendarEntryRequest(inherited.date(),venue.getId(),"event","Manuell geändert","9 €",party.getId(),"00:30",null,Set.of());
        mockMvc.perform(requireNonNull(put("/api/v1/calendar-entries/{id}",inherited.id()).contentType(MediaType.APPLICATION_JSON).content(JsonValues.write(objectMapper,update)))).andExpect(status().isOk());
        party.setDefaults(LocalTime.of(20,0),30);partySeriesRepository.saveAndFlush(party);
        assertThat(getEntries()).anySatisfy(item->{assertThat(item.id()).isEqualTo(inherited.id());assertThat(item.startTime()).isEqualTo("00:30");assertThat(item.entryPrice()).isEqualTo("9 €");});
    }

    @Test
    void persistsAndUpdatesClosureWithoutEventFields() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = createVenue("NC Closure Venue " + suffix);

        CalendarEntryResponse created = createEntry(request(
                "2027-02-14", venue.getId(), "closure", "Privat", null, null, "Betriebsferien", Set.of()
        ));
        entryIds.add(created.id());
        assertThat(created.type()).isEqualTo("closure");
        assertThat(created.partyId()).isNull();
        assertThat(created.mentionDjIds()).isEmpty();

        mockMvc.perform(requireNonNull(put("/api/v1/calendar-entries/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "2027-02-15", venue.getId(), "closure", "Geändert", null, null, "Geschlossen", Set.of()
                        )))))
                .andExpect(status().isOk());
        assertThat(getEntries()).extracting(entry -> entry.reason()).contains("Geschlossen");

        mockMvc.perform(delete("/api/v1/calendar-entries/{id}", created.id()))
                .andExpect(status().isNoContent());
        entryIds.remove(created.id());
    }

    @Test
    void rejectsInvalidReferencesAndShapesWithoutPersistingPartialEntries() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = createVenue("NC Invalid Calendar Venue " + suffix);
        Actor organizer = createActor("NC Calendar Organizer " + suffix, "organizer_user");

        long before = calendarEntryRepository.count();
        mockMvc.perform(requireNonNull(post("/api/v1/calendar-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "2027-01-01", venue.getId(), "event", "", "missing-party", "20:00", null,
                                Set.of(organizer.getId())
                        )))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(requireNonNull(post("/api/v1/calendar-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "2027-01-01", venue.getId(), "closure", "", null, null, "Geschlossen",
                                Set.of(organizer.getId())
                        )))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(requireNonNull(post("/api/v1/calendar-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request(
                                "2027-01-01", venue.getId(), "event", "", null, "20:00", null, Set.of()
                        )))))
                .andExpect(status().isBadRequest());

        assertThat(calendarEntryRepository.count()).isEqualTo(before);
    }

    private Venue createVenue(String name) {
        Venue venue = venueRepository.saveAndFlush(new Venue(name, "venue_user"));
        venueIds.add(venue.getId());
        return venue;
    }

    private Actor createActor(String name, String instagram) {
        Actor actor = actorRepository.saveAndFlush(new Actor(name, "person", instagram, Set.of("dj"), Set.of()));
        actorIds.add(actor.getId());
        return actor;
    }

    private PartySeries createParty(String name, Actor dj) {
        PartySeries party = partySeriesRepository.saveAndFlush(new PartySeries(
                name, "party_user", "#123456", "manual", null, null, null, null,
                List.of(new PartyDjAssignment(dj.getId(), "resident")), Set.of()
        ));
        partyIds.add(party.getId());
        return party;
    }

    private CalendarEntryResponse createEntry(CalendarEntryRequest request) throws Exception {
        String body = mockMvc.perform(requireNonNull(post("/api/v1/calendar-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonValues.write(objectMapper,request))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, CalendarEntryResponse.class);
    }

    private CalendarEntryResponse[] getEntries() throws Exception {
        String body = mockMvc.perform(get("/api/v1/calendar-entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper,body, CalendarEntryResponse[].class);
    }

    private CalendarEntryRequest request(String date, String locationId, String type,
                                         String notes, String partyId, String startTime,
                                         String reason, Set<String> mentionDjIds) {
        return new CalendarEntryRequest(
                date, locationId, type, notes, partyId, startTime, reason, mentionDjIds
        );
    }
}
