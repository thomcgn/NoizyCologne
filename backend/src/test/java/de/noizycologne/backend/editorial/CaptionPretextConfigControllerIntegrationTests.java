package de.noizycologne.backend.editorial;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import de.noizycologne.backend.json.JsonValues;
import de.noizycologne.backend.security.ApiBoundaryFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "noizy.api.trust.secret=isolated-caption-config-fixture-key-not-for-production",
        "noizy.test.trusted-defaults=false",
        "noizy.discovery.enabled=false",
        "noizy.discovery.monitor.enabled=false",
        "logging.level.root=WARN"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class CaptionPretextConfigControllerIntegrationTests {

    private static final String URL = "/api/v1/editorial-config/caption-pretext";
    private static final String KEY = "isolated-caption-config-fixture-key-not-for-production";
    private static final String ORIGIN = "https://tsa.noizycologne.de";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restoreDefault() {
        jdbc.update("UPDATE editorial_caption_config SET pretext=?, revision=1 WHERE singleton",
                CaptionPretextConfigService.DEFAULT_PRETEXT);
    }

    private @NonNull MockHttpServletRequestBuilder trusted(@NonNull String method) {
        return requireNonNull(request(HttpMethod.valueOf(method), URL)
                .header(ApiBoundaryFilter.HEADER, KEY)
                .header("X-Noizy-Proxy-Host", "tsa.noizycologne.de")
                .header("X-Noizy-Proxy-Proto", "https")
                .header("Origin", ORIGIN)
                .header("Sec-Fetch-Site", "same-origin"));
    }

    private @NonNull MockHttpServletRequestBuilder write(@NonNull String pretext, @Nullable Long expectedRevision) {
        CaptionPretextRequest body = new CaptionPretextRequest(pretext, expectedRevision);
        return requireNonNull(trusted("PUT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonValues.write(objectMapper, body)));
    }

    private @NonNull CaptionPretextResponse read() throws Exception {
        String content = mvc.perform(trusted("GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return JsonValues.readRequired(objectMapper, content, CaptionPretextResponse.class);
    }

    @Test
    void authenticatedReadReturnsTheSeededDefaultWithoutCaching() throws Exception {
        CaptionPretextResponse config = read();
        assertThat(config.pretext()).isEqualTo("noizycologne");
        assertThat(config.revision()).isPositive();
    }

    @Test
    void proxyKeyAndOriginAreEnforcedBeforeTheController() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isForbidden());
        mvc.perform(requireNonNull(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"pretext\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(requireNonNull(put(URL)
                        .header(ApiBoundaryFilter.HEADER, KEY)
                        .header("X-Noizy-Proxy-Host", "tsa.noizycologne.de")
                        .header("X-Noizy-Proxy-Proto", "https")
                        .header("Origin", "https://evil.example")
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pretext\":\"x\"}")))
                .andExpect(status().isForbidden());
        assertThat(read().pretext()).isEqualTo("noizycologne");
    }

    @Test
    void rejectsBlankOverlongAndSecretLikeValuesWithoutChangingTheConfig() throws Exception {
        long before = read().revision();
        mvc.perform(write("   ", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(write(requireNonNull("a".repeat(101)), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(write("ghp_" + "a".repeat(32), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(write("api_key=geheim", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(write("https://user:password@example.org", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(read().revision()).isEqualTo(before);
    }

    @Test
    void revisionGuardsConcurrentChangesAndIdenticalWritesAreIdempotent() throws Exception {
        CaptionPretextResponse initial = read();
        String content = mvc.perform(write("Team Pretext", Long.valueOf(initial.revision())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CaptionPretextResponse first = JsonValues.readRequired(objectMapper, content, CaptionPretextResponse.class);
        assertThat(first.pretext()).isEqualTo("Team Pretext");
        assertThat(first.revision()).isEqualTo(initial.revision() + 1);

        CaptionPretextResponse again = JsonValues.readRequired(objectMapper,
                mvc.perform(write("Team Pretext", Long.valueOf(first.revision())))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CaptionPretextResponse.class);
        assertThat(again.revision()).isEqualTo(first.revision());

        mvc.perform(write("Veralteter Stand", Long.valueOf(initial.revision())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        CaptionPretextResponse latest = JsonValues.readRequired(objectMapper,
                mvc.perform(write("Neuer Team Pretext", null))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CaptionPretextResponse.class);
        assertThat(latest.revision()).isEqualTo(first.revision() + 1);
        assertThat(read().pretext()).isEqualTo("Neuer Team Pretext");
    }
}
