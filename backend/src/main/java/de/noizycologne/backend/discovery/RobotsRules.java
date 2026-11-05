package de.noizycologne.backend.discovery;

/** Conservative subset: all matching wildcard/bot groups apply; unsupported patterns block rather than bypass. */
final class RobotsRules {
    static boolean allowed(String robots, String path) {
        boolean applies = false, rulesStarted = false;
        for (String raw : robots.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            int colon = line.indexOf(':'); if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (rulesStarted) { applies = false; rulesStarted = false; }
                applies |= value.equals("*") || "noizycolognediscovery".contains(value.toLowerCase(java.util.Locale.ROOT));
            } else {
                rulesStarted = true;
                if (applies && key.equals("disallow") && !value.isEmpty()) {
                    String prefix = value.split("[\\*$]", 2)[0];
                    if (path.startsWith(prefix)) return false;
                }
                // Be conservative about sites asking for a slower crawl than our fixed 2 requests/sec.
                if (applies && key.equals("crawl-delay")) return false;
            }
        }
        return true;
    }
}
