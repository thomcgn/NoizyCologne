package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
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
    public static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;
    public static final int HARD_MAX_BYTES = 4 * 1024 * 1024;
    private final int maxBytes;
    public EventPageFetcher() { this(DEFAULT_MAX_BYTES); }
    @org.springframework.beans.factory.annotation.Autowired
    public EventPageFetcher(@org.springframework.beans.factory.annotation.Value("${noizy.discovery.max-response-bytes:2097152}") int maxBytes) {
        if (maxBytes < 64 * 1024 || maxBytes > HARD_MAX_BYTES)
            throw new IllegalArgumentException("Discovery-Antwortlimit muss zwischen 64 KiB und 4 MiB liegen");
        this.maxBytes = maxBytes;
    }
    public record Page(URI url, @NonNull String body, String contentType) { }
    public static class FetchFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final String status;
        public FetchFailure(String status) { super(status); this.status = status; }
        public FetchFailure(String status, String detail) { super(detail); this.status = status; }
    }
    record Response(int status, String location, String type, byte[] body) { }
    public synchronized Page fetch(URI url) throws IOException {
        SafeUrls.parse(url.toString());
        return fetch(url, new HashMap<>(), url.getPath().equals("/robots.txt"));
    }
    private Page fetch(URI input, Map<String,String> robots, boolean robotsRequest) throws IOException {
        URI url=input;
        Set<URI> seen=new HashSet<>();
        for (int redirects=0; redirects<=5; redirects++) {
            SafeUrls.parse(url.toString());
            if (!seen.add(url)) throw new FetchFailure("REDIRECT_REQUIRES_REVIEW","Weiterleitungsschleife bei " + url);
            Response response;
            try { response=request(url); }
            catch (FetchFailure e) { throw e; }
            catch (UnknownHostException e) { throw new FetchFailure("DNS_FAILED","DNS-Auflösung oder öffentliche Zieladresse nicht zulässig: " + url + " (" + e.getMessage() + ")"); }
            catch (javax.net.ssl.SSLException e) { throw new FetchFailure("TLS_FAILED","TLS-Verbindung konnte nicht sicher hergestellt werden: " + url + " (" + e.getMessage() + ")"); }
            catch (java.net.SocketTimeoutException e) { throw new FetchFailure("FETCH_TIMEOUT","Zeitlimit bei Verbindung oder Antwort: " + url); }
            catch (IOException e) { throw new FetchFailure("SCAN_FAILED","HTTP-Verbindung fehlgeschlagen: " + url + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"); }
            int code=response.status();
            if (List.of(301,302,303,307,308).contains(code)) {
                if (redirects==5) throw new FetchFailure("REDIRECT_REQUIRES_REVIEW","Mehr als fünf Weiterleitungen: " + url);
                URI target;
                try {
                    if (response.location().isBlank()) throw new IllegalArgumentException();
                    target=SafeUrls.parse(url.resolve(response.location()).toString());
                } catch (IllegalArgumentException e) { throw new FetchFailure("UNSAFE_URL","HTTP " + code + ": unzulässiges Weiterleitungsziel bei " + url); }
                // Destination DNS is checked by request's resolver before any connection, including robots.txt.
                if (!robotsRequest && !robotsAllowed(target,robots))
                    throw new FetchFailure("ROBOTS_BLOCKED","Weiterleitungsziel durch robots.txt gesperrt: " + target);
                url=target;
                continue;
            }
            if (code==404 && robotsRequest) return new Page(url,"","text/plain");
            if (code==401 || code==403 || code==429) throw new FetchFailure("BLOCKED","HTTP " + code + ": Zugriff gesperrt bei " + url);
            if (code!=200) throw new FetchFailure("SCAN_FAILED","HTTP " + code + " beim Abruf von " + url);
            String type=response.type().toLowerCase(Locale.ROOT);
            if (!(type.startsWith("text/") || type.contains("json") || type.contains("calendar")))
                throw new FetchFailure("UNSUPPORTED_CONTENT","Nicht unterstützter Content-Type „" + response.type() + "“ bei " + url);
            return new Page(url,new String(response.body(),StandardCharsets.UTF_8),response.type());
        }
        throw new FetchFailure("REDIRECT_REQUIRES_REVIEW");
    }
    /** One request only: never let the HTTP library follow unchecked redirects or use a proxy. */
    Response request(URI url) throws IOException {
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
            request.setHeader("Accept-Encoding", "gzip, deflate");
            return client.execute(request, response -> {
                int code=response.getCode();
                var location=response.getFirstHeader("Location");
                var entity=response.getEntity();
                String type=entity==null ? "" : Objects.toString(entity.getContentType(),"");
                byte[] body=new byte[0];
                if (code==200 && entity!=null) {
                    if(entity.getContentLength()>maxBytes) throw new FetchFailure("RESPONSE_TOO_LARGE","Antwort überschreitet Größenlimit bei " + url);
                    try(var in=entity.getContent()) {
                        String encoding=Objects.toString(entity.getContentEncoding(),"identity").trim().toLowerCase(Locale.ROOT);
                        body=readBody(decoded(in,encoding),System.nanoTime()+10_000_000_000L);
                    }
                }
                return new Response(code,location==null ? "" : location.getValue(),type,body);
            });
        }
    }
    java.io.InputStream decoded(java.io.InputStream in,String encoding) throws IOException {
        return switch(encoding) {
            case "", "identity" -> in;
            case "gzip", "x-gzip" -> new java.util.zip.GZIPInputStream(in);
            case "deflate" -> new java.util.zip.InflaterInputStream(in);
            default -> throw new FetchFailure("UNSUPPORTED_ENCODING","Content-Encoding „" + encoding + "“ wird nicht unterstützt");
        };
    }
    byte[] readBody(java.io.InputStream in, long deadline) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            // Bound decompressed bytes too, even with absent or misleading Content-Length.
            if (out.size() + n > maxBytes) throw new FetchFailure("RESPONSE_TOO_LARGE");
            if (System.nanoTime() > deadline) throw new FetchFailure("FETCH_TIMEOUT");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public boolean robotsAllowed(URI url, Map<String, String> cache) throws IOException {
        SafeUrls.parse(url.toString());
        String origin = "https://" + url.getHost();
        if (!cache.containsKey(origin)) {
            try { cache.put(origin,fetch(URI.create(origin + "/robots.txt"),cache,true).body()); }
            catch(IOException e) {
                throw new FetchFailure(e instanceof FetchFailure f ? f.status : "SCAN_FAILED","robots.txt konnte nicht geprüft werden für " + origin + ": " + e.getMessage());
            }
        }
        return RobotsRules.allowed(cache.get(origin), url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }
}
