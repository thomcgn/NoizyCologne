package de.noizycologne.backend.discovery;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Validation is repeated by the connection's DNS resolver, never just before a second lookup. */
public final class SafeUrls {
    public static final int MAX_URL_LENGTH = 4096;
    // Generic tracking/telemetry query keys; no venue- or club-specific rules.
    private static final Set<String> TRACKING_KEYS = Set.of("utm_source", "utm_medium", "utm_campaign", "utm_term",
        "utm_content", "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_cid", "mc_eid", "_ga", "_gl", "igshid", "yclid");
    private SafeUrls() { }
    public static void validateOptional(String value) { if (value != null && !value.isBlank()) parse(value); }
    /** Drop tracking/empty/duplicate query pairs without changing origin, path or method. */
    static String canonical(String value) {
        URI u = URI.create(value);
        String query = u.getRawQuery(), authority = u.getRawAuthority();
        if (query == null || query.isEmpty() || authority == null) return value;
        List<String[]> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String key;
            try { key = URLDecoder.decode(rawKey.replace("+", " "), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT); }
            catch (RuntimeException e) { key = rawKey.trim().toLowerCase(Locale.ROOT); }
            if (key.isEmpty() || TRACKING_KEYS.contains(key) || !seen.add(key)) continue;
            kept.add(new String[]{key, pair});
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        return u.getScheme() + "://" + authority + path + "?" + kept.stream().map(item -> item[1]).collect(Collectors.joining("&"));
    }
    public static URI parse(String value) {
        try {
            String canonical = canonical(value);
            URI u = URI.create(canonical).normalize();
            if (value.length() > MAX_URL_LENGTH || canonical.length() > MAX_URL_LENGTH
                    || !"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)
                    || u.getFragment() != null || !u.getHost().contains(".")) throw new IllegalArgumentException();
            String host = u.getHost();
            if (host.endsWith(".") || host.contains(":") || host.matches("[0-9.]+")) throw new IllegalArgumentException();
            return u;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Nur öffentliche HTTPS-URLs ohne Zugangsdaten, Fragment oder Sonderport sind erlaubt"); }
    }
    public static InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) if (!isPublic(address)) throw new UnknownHostException("Nicht öffentliche Zieladresse gesperrt");
        return addresses;
    }
    public static boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 16) {
            // Only global unicast; reject documentation, transition and special allocations.
            return (a & 0xe0) == 0x20 && !(a == 0x20 && c == 0x01 && ((b[2] & 255) < 2 || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
                    && !(a == 0x20 && c == 0x02) && !(a == 0x3f && c == 0xff);
        }
        return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c >= 64 && c <= 127)
                || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && (c == 0 || c == 168 || c == 2 || c == 88))
                || (a == 198 && (c == 18 || c == 19 || c == 51)) || (a == 203 && c == 0));
    }
}
