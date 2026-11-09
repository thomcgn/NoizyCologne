package de.noizycologne.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.jspecify.annotations.NonNull;
import static java.util.Objects.requireNonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Controller integration fixtures exercise the filter with a configured internal identity. */
@Component
@ConditionalOnProperty(name="noizy.test.trusted-defaults", havingValue="true", matchIfMissing=true)
public class TrustedMvcRequests implements MockMvcBuilderCustomizer {
    private final String secret;
    public TrustedMvcRequests(@Value("${noizy.api.trust.secret:}") String secret) {this.secret=secret;}
    @Override public void customize(@NonNull ConfigurableMockMvcBuilder<?> builder) {
        if(secret.length()>=32) builder.defaultRequest(requireNonNull(get("/").header(ApiBoundaryFilter.HEADER, secret)
            .header("Origin", "https://tsa.noizycologne.de").header("X-Noizy-Proxy-Host", "tsa.noizycologne.de")
            .header("X-Noizy-Proxy-Proto", "https").header("Sec-Fetch-Site", "same-origin")));
    }
}
