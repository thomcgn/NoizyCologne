package de.noizycologne.backend.discovery;

import java.net.URI;
import java.util.*;

/** Framework transport parameters do not identify an event or a source. Applies to every host. */
public final class CanonicalSourceUrl {
    private CanonicalSourceUrl() { }
    public static String reference(String value) {
        int fragment=value.indexOf('#');return fragment<0?of(value):of(value.substring(0,fragment))+value.substring(fragment);
    }
    public static String of(String value) {
        URI uri=SafeUrls.parse(value.trim());
        String query=uri.getRawQuery();
        if(query!=null) query=Arrays.stream(query.split("&")).filter(part -> !java.net.URLDecoder.decode(part.split("=",2)[0],java.nio.charset.StandardCharsets.UTF_8).equals("_rsc")).collect(java.util.stream.Collectors.joining("&"));
        return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(query==null || query.isEmpty()?"":"?"+query);
    }
}
