package de.noizycologne.backend.discovery;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class EventPageFetcher {
    public static final int MAX_BYTES = 1_000_000;
    public record Page(URI url, String body, String contentType) { }
    public static class FetchFailure extends IOException {
        public final String status;
        public FetchFailure(String status) { super(status); this.status = status; }
    }
    public synchronized Page fetch(URI url) throws IOException {
        SafeUrls.parse(url.toString());
        // A fresh connection uses the checked DNS results directly. No proxy, redirects, cookies or decompression.
        DnsResolver resolver = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException { return SafeUrls.resolve(host); }
            public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(8)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(8)).build()).build()) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            var request = new HttpGet(url);
            request.setHeader("User-Agent", "NoizyCologneDiscovery/1.0");
            request.setHeader("Accept", "text/html, application/ld+json, text/calendar, text/plain");
            return client.execute(request, response -> {
                int code = response.getCode();
                if (code == 404 && url.getPath().equals("/robots.txt")) return new Page(url, "", "text/plain");
                if (code == 401 || code == 403 || code == 429) throw new FetchFailure("BLOCKED");
                // Redirects are intentionally reported, never followed to an unchecked domain/IP.
                if (code >= 300 && code < 400) throw new FetchFailure("REDIRECT_REQUIRES_REVIEW");
                if (code != 200 || response.getEntity() == null) throw new FetchFailure("SCAN_FAILED");
                var entity = response.getEntity();
                String type = Objects.toString(entity.getContentType(), "");
                if (response.containsHeader("Content-Encoding")) throw new FetchFailure("UNSUPPORTED_ENCODING");
                if (entity.getContentLength() > MAX_BYTES) throw new FetchFailure("RESPONSE_TOO_LARGE");
                long deadline = System.nanoTime() + 10_000_000_000L;
                var out = new java.io.ByteArrayOutputStream();
                try (var in = entity.getContent()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > MAX_BYTES || System.nanoTime() > deadline) throw new FetchFailure("RESPONSE_LIMIT");
                        out.write(buffer, 0, n);
                    }
                }
                if (!(type.contains("text/") || type.contains("json") || type.contains("calendar"))) throw new FetchFailure("UNSUPPORTED_CONTENT");
                return new Page(url, out.toString(StandardCharsets.UTF_8), type);
            });
        }
    }
    public boolean robotsAllowed(URI url, Map<String, String> cache) throws IOException {
        String origin = "https://" + url.getHost();
        if (!cache.containsKey(origin)) cache.put(origin, fetch(URI.create(origin + "/robots.txt")).body());
        return RobotsRules.allowed(cache.get(origin), url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }
}
