package de.noizycologne.backend.media;

import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = "noizy.media.root=target/test-party-flyers")
class PartyFlyerControllerIntegrationTests {

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PartySeriesRepository partySeriesRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    private final List<String> partyIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        partyIds.forEach(id -> partySeriesRepository.deleteById(id));
        partySeriesRepository.flush();
        assetIds.forEach(id -> mediaAssetRepository.deleteById(id));
        mediaAssetRepository.flush();
        Path root = Path.of("target/test-party-flyers");
        if (Files.exists(root)) {
            try (var files = Files.list(root)) {
                files.forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void uploadsReplacesReadsAndDeletesPersistentDefaultFlyer() throws Exception {
        String suffix = UUID.randomUUID().toString();
        PartySeries party = partySeriesRepository.saveAndFlush(new PartySeries(
                "Flyer Party " + suffix, "", "#123456", "manual", null,
                null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());

        MockMultipartFile first = new MockMultipartFile(
                "file", "first.png", MediaType.IMAGE_PNG_VALUE, PNG);
        String firstResponse = mockMvc.perform(multipart("/api/v1/parties/{id}/default-flyer", party.getId())
                        .file(first))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode firstJson = objectMapper.readTree(firstResponse);
        String firstAssetId = firstJson.get("id").stringValue();
        assetIds.add(firstAssetId);
        assertThat(firstJson.get("originalFilename").stringValue()).isEqualTo("first.png");

        JsonNode parties = objectMapper.readTree(mockMvc.perform(get("/api/v1/parties"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode persisted = null;
        for (JsonNode item : parties) {
            if (party.getId().equals(item.get("id").stringValue())) persisted = item;
        }
        assertThat(persisted).isNotNull();
        JsonNode persistedParty = Objects.requireNonNull(persisted, "Persisted party was not returned by the API");
        assertThat(persistedParty.get("defaultFlyer").get("id").stringValue()).isEqualTo(firstAssetId);

        mockMvc.perform(get("/api/v1/parties/{id}/default-flyer", party.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG));

        MockMultipartFile replacement = new MockMultipartFile(
                "file", "replacement.png", MediaType.IMAGE_PNG_VALUE, PNG);
        String replacementResponse = mockMvc.perform(multipart("/api/v1/parties/{id}/default-flyer", party.getId())
                        .file(replacement))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacementAssetId = objectMapper.readTree(replacementResponse).get("id").stringValue();
        assertThat(replacementAssetId).isNotEqualTo(firstAssetId);
        assetIds.add(replacementAssetId);

        mockMvc.perform(delete("/api/v1/parties/{id}/default-flyer", party.getId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/parties/{id}/default-flyer", party.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonImagesBeforePersistence() throws Exception {
        String suffix = UUID.randomUUID().toString();
        PartySeries party = partySeriesRepository.saveAndFlush(new PartySeries(
                "Invalid Flyer Party " + suffix, "", "#654321", "manual", null,
                null, null, null, Set.of(), Set.of()));
        partyIds.add(party.getId());
        long assetCountBefore = mediaAssetRepository.count();

        MockMultipartFile invalid = new MockMultipartFile(
                "file", "script.html", MediaType.TEXT_HTML_VALUE, "<script>".getBytes());
        mockMvc.perform(multipart("/api/v1/parties/{id}/default-flyer", party.getId())
                        .file(invalid))
                .andExpect(status().isBadRequest());
        assertThat(mediaAssetRepository.count()).isEqualTo(assetCountBefore);
    }
}
