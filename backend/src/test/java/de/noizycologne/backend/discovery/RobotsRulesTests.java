package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.assertj.core.api.Assertions.assertThat;

class RobotsRulesTests {
    @Test void blueShellAllowsHomepageAndCalendarButBlocksLightbox() throws Exception {
        String robots;
        try (var input = getClass().getResourceAsStream("/discovery/blue-shell-robots.txt")) {
            robots = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(RobotsRules.allowed(robots, "/")).isTrue();
        assertThat(RobotsRules.allowed(robots, "/programm")).isTrue();
        assertThat(RobotsRules.allowed(robots, "/?lightbox=concert")).isFalse();
        assertThat(RobotsRules.allowed(robots, "/programm?lightbox=concert")).isFalse();
    }
    @Test void longestRuleWinsAndAllowWinsTies() {
        String robots = "User-agent: *\nDisallow: /\nAllow: /events\nDisallow: /events/private\nAllow: /events/private/public";
        assertThat(RobotsRules.allowed(robots, "/")).isFalse();
        assertThat(RobotsRules.allowed(robots, "/events/one")).isTrue();
        assertThat(RobotsRules.allowed(robots, "/events/private/one")).isFalse();
        assertThat(RobotsRules.allowed(robots, "/events/private/public/one")).isTrue();
        assertThat(RobotsRules.allowed("User-agent: *\nDisallow: /same\nAllow: /same", "/same")).isTrue();
        assertThat(RobotsRules.allowed("User-agent: *\nAllow: /same\nDisallow: /same", "/same")).isTrue();
    }
    @Test void wildcardAndEndAnchorAreMatchedAsPatterns() {
        String robots = "User-agent: *\nDisallow: /*.pdf$\nDisallow: /private/*/secret";
        assertThat(RobotsRules.allowed(robots, "/flyer.pdf")).isFalse();
        assertThat(RobotsRules.allowed(robots, "/flyer.pdf?download=1")).isTrue();
        assertThat(RobotsRules.allowed(robots, "/private/a/b/secret/more")).isFalse();
        assertThat(RobotsRules.allowed(robots, "/private/a/public")).isTrue();
        assertThat(RobotsRules.allowed("User-agent: *\nDisallow: /file?x=[a].", "/file?x=aZ")).isTrue();
    }
    @Test void specificGroupsOverrideWildcardAndMatchingGroupsAreMerged() {
        String robots = "User-agent: *\nDisallow: /\nUser-agent: NoizyCologneDiscovery\nAllow: /events\nUser-agent: noizycolognediscovery\nDisallow: /private";
        assertThat(RobotsRules.allowed(robots, "/events")).isTrue();
        assertThat(RobotsRules.allowed(robots, "/private")).isFalse();
    }
    @Test void unrelatedBotsAndDelaysDoNotBlockUs() {
        String robots = "User-agent: *\nAllow: /\nUser-agent: PetalBot\nDisallow: /\nUser-agent: dotbot\nCrawl-delay: 10";
        assertThat(RobotsRules.allowed(robots, "/")).isTrue();
        assertThat(RobotsRules.allowed("User-agent: *\nCrawl-delay: 10", "/")).isFalse();
    }
    @Test void emptyRulesCommentsAndConsecutiveAgents() {
        assertThat(RobotsRules.allowed("Disallow: /\nUser-agent: *\nDisallow: # empty", "/")).isTrue();
        assertThat(RobotsRules.allowed("User-agent: other\nUser-agent: NoizyCologneDiscovery\nDisallow: /private # comment", "/private")).isFalse();
    }
}
