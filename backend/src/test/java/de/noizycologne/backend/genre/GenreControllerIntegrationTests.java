package de.noizycologne.backend.genre;

import de.noizycologne.backend.media.GenreLexicon;
import de.noizycologne.backend.media.TesseractOcrService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class GenreControllerIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GenreRepository genreRepository;
    @Autowired private GenreAliasRepository aliasRepository;
    @Autowired private GenreLexicon lexicon;

    private final List<String> genreIds = new ArrayList<>();
    private final List<String> aliasIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        aliasRepository.deleteAllById(aliasIds);
        aliasRepository.flush();
        genreRepository.deleteAllById(genreIds);
        genreRepository.flush();
        lexicon.refresh();
    }

    @Test
    void migratesElectronicoreAndEmoAndSupportsAliasCrudWithCacheRefresh() throws Exception {
        String initial = mockMvc.perform(get("/api/v1/genres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.canonicalName == 'Electronicore')]").exists())
                .andExpect(jsonPath("$[?(@.canonicalName == 'Emo')]").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(initial).contains("Electronicore", "Emo");

        String suffix = UUID.randomUUID().toString();
        String createdBody = mockMvc.perform(post("/api/v1/genres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenreRequest("Testcore" + suffix, "Testcore " + suffix, true))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        GenreResponse created = objectMapper.readValue(createdBody, GenreResponse.class);
        genreIds.add(created.id());

        String aliasBody = mockMvc.perform(post("/api/v1/genres/{id}/aliases", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"test core\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        GenreAliasResponse alias = objectMapper.readValue(aliasBody, GenreAliasResponse.class);
        aliasIds.add(alias.id());

        assertThat(lexicon.findGenres(List.of(word("test"), word("core")))).contains("Testcore" + suffix);

        mockMvc.perform(post("/api/v1/genres/{id}/aliases", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"TEST-CORE\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/genres/{genreId}/aliases/{aliasId}", created.id(), alias.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"testcore updated\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/genres/{genreId}/aliases/{aliasId}", created.id(), alias.id()))
                .andExpect(status().isNoContent());
        aliasIds.clear();
    }

    @Test
    void deactivatingGenreRemovesItFromTheNextOcrSnapshot() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String body = mockMvc.perform(post("/api/v1/genres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenreRequest("Ephemeral" + suffix, "Ephemeral " + suffix, true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        GenreResponse genre = objectMapper.readValue(body, GenreResponse.class);
        genreIds.add(genre.id());
        String aliasBody = mockMvc.perform(post("/api/v1/genres/{id}/aliases", genre.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"ephemeral alias\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        aliasIds.add(objectMapper.readValue(aliasBody, GenreAliasResponse.class).id());

        mockMvc.perform(put("/api/v1/genres/{id}", genre.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenreRequest(genre.canonicalName(), genre.displayName(), false))))
                .andExpect(status().isOk());
        assertThat(lexicon.findGenres(List.of(word("ephemeral"), word("alias")))).doesNotContain(genre.canonicalName());
    }

    private TesseractOcrService.OcrWord word(String value) {
        return new TesseractOcrService.OcrWord(value, 95, 0, 0, 10, 10, "test", 11, 1, 1, 1);
    }
}
