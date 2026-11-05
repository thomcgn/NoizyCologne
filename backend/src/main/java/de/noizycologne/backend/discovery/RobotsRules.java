package de.noizycologne.backend.discovery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Matches robots path globs without executing regular expressions supplied by websites. */
final class RobotsRules {
    private static final String BOT = "noizycolognediscovery";
    private record Rule(String pattern, boolean allow) {
        int specificity() {
            return pattern.replace("*", "").replaceAll("\\$$", "").getBytes(StandardCharsets.UTF_8).length;
        }
    }
    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        boolean delay;
        boolean started;
    }

    static boolean allowed(String robots, String path) {
        List<Group> groups = new ArrayList<>();
        Group group = null;
        for (String raw : robots.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (group == null || group.started) {
                    group = new Group(); groups.add(group);
                }
                group.agents.add(value.toLowerCase(Locale.ROOT));
            } else if (group != null && (key.equals("allow") || key.equals("disallow") || key.equals("crawl-delay"))) {
                group.started = true;
                if (key.equals("crawl-delay")) group.delay = true;
                else if (!value.isEmpty()) group.rules.add(new Rule(value, key.equals("allow")));
            }
        }
        // Explicit product-token groups take precedence over wildcard groups.
        boolean specific = groups.stream().anyMatch(g -> g.agents.contains(BOT));
        int longest = -1;
        boolean allow = true;
        for (Group g : groups) {
            if (!(specific ? g.agents.contains(BOT) : g.agents.contains("*"))) continue;
            // Delays remain conservatively blocked until the fetcher supports per-host pacing.
            if (g.delay) return false;
            for (Rule rule : g.rules) {
                if (!matches(rule.pattern(), path)) continue;
                int length = rule.specificity();
                if (length > longest) { longest = length; allow = rule.allow(); }
                else if (length == longest && rule.allow()) allow = true;
            }
        }
        return allow;
    }

    /** '*' matches any sequence; a final '$' anchors the end; other characters are literals. */
    private static boolean matches(String pattern, String path) {
        boolean end = pattern.endsWith("$");
        if (end) pattern = pattern.substring(0, pattern.length() - 1);
        int p = 0, t = 0, star = -1, retry = 0;
        while (t < path.length()) {
            if (p == pattern.length() && !end) return true;
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; retry = t;
            } else if (p < pattern.length() && pattern.charAt(p) == path.charAt(t)) {
                p++; t++;
            } else if (star >= 0) {
                p = star + 1; t = ++retry;
            } else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
