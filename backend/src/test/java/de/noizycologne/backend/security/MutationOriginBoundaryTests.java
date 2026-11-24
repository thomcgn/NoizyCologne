package de.noizycologne.backend.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the real servlet filter in a MockMvc chain; no Spring context or database is needed. */
class MutationOriginBoundaryTests {

    private static final String KEY = "isolated-origin-fixture-key-not-for-production";
    private static final String ORIGIN = "https://tsa.noizycologne.de";

    @RestController
    static class FixtureController {
        final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/api/v1/actors")
        void create() {
            calls.incrementAndGet();
        }
    }

    private final FixtureController controller = new FixtureController();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new ApiBoundaryFilter(KEY, "", ORIGIN))
            .build();

    private static @NonNull MockHttpServletRequestBuilder mutation(@Nullable String origin) {
        var request = requireNonNull(post("/api/v1/actors"))
                .header(ApiBoundaryFilter.HEADER, KEY)
                .header("X-Noizy-Proxy-Host", "tsa.noizycologne.de")
                .header("X-Noizy-Proxy-Proto", "https")
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (origin != null) {
            request.header("Origin", origin);
        }
        return Objects.requireNonNull(request);
    }

    @Test
    void mutationWithoutOriginIsDeniedBeforeReachingTheController() throws Exception {
        var result = mvc.perform(mutation(null)).andExpect(status().isForbidden()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Anfrage nicht autorisiert");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(controller.calls.get()).isZero();
    }

    @Test
    void mutationWithForeignOriginIsDeniedBeforeReachingTheController() throws Exception {
        mvc.perform(mutation("https://evil.example")).andExpect(status().isForbidden());

        assertThat(controller.calls.get()).isZero();
    }

    @Test
    void mutationWithExactConfiguredOriginStillReachesTheController() throws Exception {
        mvc.perform(mutation(ORIGIN)).andExpect(status().isOk());

        assertThat(controller.calls.get()).isEqualTo(1);
    }
}
