package de.noizycologne.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import static java.util.Objects.requireNonNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"noizy.api.trust.secret=isolated-api-boundary-fixture-key-not-for-production","noizy.test.trusted-defaults=false","noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiBoundaryIntegrationTests {
    @Autowired MockMvc mvc;
    @Test void actualServletFilterProtectsReadsAndWritesBeforeControllers() throws Exception {
        mvc.perform(get("/api/v1/venues")).andExpect(status().isForbidden());
        mvc.perform(requireNonNull(post("/api/v1/venues").contentType("application/json").content("{}"))).andExpect(status().isForbidden());
        mvc.perform(requireNonNull(get("/api/v1/venues").header(ApiBoundaryFilter.HEADER,ApiBoundaryTests.KEY)
            .header("X-Noizy-Proxy-Host","tsa.noizycologne.de").header("X-Noizy-Proxy-Proto","https"))).andExpect(status().isOk());
        mvc.perform(requireNonNull(post("/api/v1/venues").header(ApiBoundaryFilter.HEADER,ApiBoundaryTests.KEY)
            .header("X-Noizy-Proxy-Host","tsa.noizycologne.de").header("X-Noizy-Proxy-Proto","https")
            .header("Origin","https://evil.example").contentType("application/json").content("{}"))).andExpect(status().isForbidden());
    }
    @Test void readinessRemainsAvailableAndInternalResetStillRequiresSeparateKey() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/internal/maintenance/factory-reset")).andExpect(status().isServiceUnavailable());
    }
}
