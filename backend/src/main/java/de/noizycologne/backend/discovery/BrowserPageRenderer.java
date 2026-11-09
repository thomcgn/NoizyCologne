package de.noizycologne.backend.discovery;

import de.noizycologne.backend.json.JsonValues;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Trusted internal control endpoint; all website sockets are confined to the browser gateway. */
@Component
public class BrowserPageRenderer {
    public record Result(String status,List<EventPageFetcher.@NonNull Page> pages,List<@NonNull String> warnings,boolean complete) { }
    private final boolean enabled;
    private final URI endpoint;
    private final String token;
    private final Semaphore lock=new Semaphore(1);
    private final JsonMapper json=JsonMapper.builder().build();
    public BrowserPageRenderer(@Value("${noizy.discovery.browser.enabled:false}") boolean enabled,
            @Value("${noizy.discovery.browser.url:http://browser-gateway:3128/render}") String endpoint,
            @Value("${noizy.discovery.browser.token:}") String token) {
        this.enabled=enabled;this.endpoint=URI.create(endpoint);this.token=token;
        if (enabled && (token.length()<32 || this.endpoint.getHost()==null
                || !List.of("http","https").contains(this.endpoint.getScheme())
                || this.endpoint.getUserInfo()!=null || this.endpoint.getRawQuery()!=null || this.endpoint.getFragment()!=null))
            throw new IllegalArgumentException("Browser-Worker benötigt einen internen Kontroll-Endpunkt und ein Token mit mindestens 32 Zeichen");
    }
    public Result render(URI website) throws IOException {
        SafeUrls.parse(website.toString());
        if (!enabled) return new Result("BROWSER_DISABLED",List.of(),List.of("BROWSER_DISABLED"),false);
        if (!lock.tryAcquire()) return new Result("BROWSER_BUSY",List.of(),List.of("BROWSER_BUSY"),false);
        try {
            var manager=PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(45)).build()).build();
            try (var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                    .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(
                        RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(45)).build()).build()) {
                var request=new HttpPost(endpoint);
                request.setHeader("Authorization","Bearer "+token);
                request.setEntity(new StringEntity(json.writeValueAsString(Map.of("url",website.toString())),ContentType.APPLICATION_JSON));
                return client.execute(request,response -> {
                    if (response.getCode()==429) return new Result("BROWSER_BUSY",List.of(),List.of("BROWSER_BUSY"),false);
                    if (response.getCode()!=200 || response.getEntity()==null) {
                        String failure=response.getCode()==401 || response.getCode()==403 ? "BROWSER_AUTH_FAILED"
                            : response.getCode()==502 || response.getCode()==503 ? "BROWSER_WORKER_UNAVAILABLE" : "BROWSER_UNAVAILABLE";
                        return new Result(failure,List.of(),List.of(failure,"Browser control HTTP "+response.getCode()),false);
                    }
                    byte[] bytes;
                    try (var stream=response.getEntity().getContent()) {
                        bytes=new EventPageFetcher(EventPageFetcher.HARD_MAX_BYTES).readBody(stream,System.nanoTime()+45_000_000_000L);
                    }
                    Result result;
                    try { result=JsonValues.readRequired(json,new String(bytes,StandardCharsets.UTF_8),Result.class); }
                    catch (RuntimeException e) { throw new IOException("Ungültige Browser-Worker-Antwort",e); }
                    if (result.pages()==null || result.warnings()==null || result.pages().size()>25)
                        throw new IOException("Unvollständige Browser-Worker-Antwort");
                    for (var page:result.pages()) {
                        SafeUrls.parse(page.url().toString());
                        if (page.body().length()>2_000_000) throw new IOException("Browser-DOM überschreitet Größenlimit");
                    }
                    // Rendered snapshots never establish that an entire calendar was examined.
                    var pages=result.pages().stream().map(page -> sameRoot(page.url(),website)
                        ? new EventPageFetcher.Page(website,page.body(),page.contentType()) : page).toList();
                    return new Result(result.status(),pages,List.copyOf(result.warnings()),false);
                });
            }
        } catch (java.net.SocketTimeoutException e) {
            return new Result("BROWSER_TIMEOUT",List.of(),List.of("BROWSER_TIMEOUT"),false);
        } catch (IOException e) {
            return new Result("BROWSER_UNAVAILABLE",List.of(),List.of("BROWSER_UNAVAILABLE"),false);
        } catch (RuntimeException e) {
            return new Result("BROWSER_FAILED",List.of(),List.of("BROWSER_FAILED"),false);
        } finally { lock.release(); }
    }
    private static boolean sameRoot(URI actual,URI requested) {
        return actual.getHost().equalsIgnoreCase(requested.getHost())
            && (actual.getPath().isEmpty() ? "/" : actual.getPath()).equals(requested.getPath().isEmpty() ? "/" : requested.getPath())
            && Objects.equals(actual.getRawQuery(),requested.getRawQuery());
    }
}
