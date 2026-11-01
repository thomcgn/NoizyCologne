package de.noizycologne.backend.media;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = "noizy.media.root=target/test-calendar-flyers")
class CalendarEntryFlyerControllerIntegrationTests {

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private static byte[] JPEG;

    static {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            JPEG = output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CalendarEntryRepository calendarEntryRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private PartySeriesRepository partySeriesRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    private final List<String> entryIds = new ArrayList<>();
    private final List<String> venueIds = new ArrayList<>();
    private final List<String> partyIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        calendarEntryRepository.deleteAllById(entryIds);
        calendarEntryRepository.flush();
        partySeriesRepository.deleteAllById(partyIds);
        partySeriesRepository.flush();
        venueRepository.deleteAllById(venueIds);
        venueRepository.flush();
        mediaAssetRepository.deleteAllById(assetIds);
        mediaAssetRepository.flush();

        Path root = Path.of("target/test-calendar-flyers");
        if (Files.exists(root)) {
            try (var files = Files.list(root)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Test cleanup must not hide the assertion result.
                    }
                });
            }
        }
    }

    @Test
    void uploadsReadsAndDeletesOnlyTheEventFlyer() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = venueRepository.saveAndFlush(new Venue("Event Flyer Venue " + suffix, "vf" + suffix.substring(0, 8)));
        venueIds.add(venue.getId());
        PartySeries party = partySeriesRepository.saveAndFlush(new PartySeries(
                "Event Flyer Party " + suffix, "pf" + suffix.substring(0, 8), "#123456", "manual",
                null, null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());
        CalendarEntry entry = calendarEntryRepository.saveAndFlush(new CalendarEntry(
                LocalDate.of(2027, 3, 12), venue.getId(), "event", "Notiz", party.getId(),
                LocalTime.of(22, 0), null, Set.of()));
        entryIds.add(entry.getId());

        MockMultipartFile upload = new MockMultipartFile(
                "file", "event.jpg", MediaType.IMAGE_JPEG_VALUE, JPEG);
        String uploadBody = mockMvc.perform(multipart("/api/v1/calendar-entries/{id}/flyer", entry.getId())
                        .file(upload))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode uploaded = objectMapper.readTree(uploadBody);
        String assetId = uploaded.get("id").asText();
        assetIds.add(assetId);
        assertThat(uploaded.get("originalFilename").asText()).isEqualTo("event.jpg");
        assertThat(uploaded.get("mediaType").asText()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);

        JsonNode entries = objectMapper.readTree(mockMvc.perform(get("/api/v1/calendar-entries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode persisted = null;
        for (JsonNode item : entries) {
            if (entry.getId().equals(item.get("id").asText())) {
                persisted = item;
                break;
            }
        }
        assertThat(persisted).isNotNull();
        assertThat(persisted.get("eventFlyer").get("id").asText()).isEqualTo(assetId);

        mockMvc.perform(get("/api/v1/calendar-entries/{id}/flyer", entry.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(JPEG));

        mockMvc.perform(delete("/api/v1/calendar-entries/{id}/flyer", entry.getId()))
                .andExpect(status().isNoContent());
        assertThat(calendarEntryRepository.findById(entry.getId()).orElseThrow().getEventFlyerMediaId())
                .isNull();

        mockMvc.perform(get("/api/v1/calendar-entries/{id}/flyer", entry.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsFlyersForClosuresAndDoesNotPersistAnAsset() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Venue venue = venueRepository.saveAndFlush(new Venue("Closure Flyer Venue " + suffix, "cf" + suffix.substring(0, 8)));
        venueIds.add(venue.getId());
        CalendarEntry entry = calendarEntryRepository.saveAndFlush(new CalendarEntry(
                LocalDate.of(2027, 3, 13), venue.getId(), "closure", "Geschlossen", null,
                null, "Privat", Set.of()));
        entryIds.add(entry.getId());
        long assetsBefore = mediaAssetRepository.count();

        MockMultipartFile upload = new MockMultipartFile(
                "file", "closure.png", MediaType.IMAGE_PNG_VALUE, PNG);
        mockMvc.perform(multipart("/api/v1/calendar-entries/{id}/flyer", entry.getId())
                        .file(upload))
                .andExpect(status().isBadRequest());

        assertThat(mediaAssetRepository.count()).isEqualTo(assetsBefore);
        assertThat(calendarEntryRepository.findById(entry.getId()).orElseThrow().getEventFlyerMediaId())
                .isNull();
    }

    @Test
    void returnsAnActionableErrorForAnUnknownAnalysisEntry() throws Exception {
        mockMvc.perform(post("/api/v1/calendar-entries/{id}/flyer/analysis", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Kalendereintrag nicht gefunden"))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
