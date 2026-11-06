package de.noizycologne.backend.discovery;

import java.net.*;

/** Validation is repeated by the connection's DNS resolver, never just before a second lookup. */
public final class SafeUrls {
    private SafeUrls() { }
    public static void validateOptional(String value) { if (value != null && !value.isBlank()) parse(value); }
    public static URI parse(String value) {
        try {
            URI u = URI.create(value).normalize();
            if (value.length() > 2048 || !"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
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
