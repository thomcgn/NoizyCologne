package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure unit checks for overview pagination bounds and SQL shape; no database is used. */
class DiscoveryOverviewPaginationTests {
    @Test void defaultsKeepThePreviousSingleRequestSize() {
        assertThat(DiscoveryStore.OVERVIEW_DEFAULT_SIZE).isEqualTo(500);
        assertThat(DiscoveryStore.OVERVIEW_MAX_SIZE).isGreaterThan(DiscoveryStore.OVERVIEW_DEFAULT_SIZE);
    }
    @Test void pageAndSizeAreClampedBeforeTheyReachSql() {
        assertThat(DiscoveryStore.normalizedPage(-5)).isZero();
        assertThat(DiscoveryStore.normalizedPage(0)).isZero();
        assertThat(DiscoveryStore.normalizedPage(7)).isEqualTo(7);
        assertThat(DiscoveryStore.normalizedSize(-1)).isZero();
        assertThat(DiscoveryStore.normalizedSize(0)).isZero();
        assertThat(DiscoveryStore.normalizedSize(500)).isEqualTo(500);
        assertThat(DiscoveryStore.normalizedSize(5000)).isEqualTo(DiscoveryStore.OVERVIEW_MAX_SIZE);
    }
    @Test void overviewQuerySortsStablyAndUsesBoundedLimitOffset() {
        assertThat(DiscoveryStore.candidatesSql("ALL")).contains("ORDER BY c.updated_at DESC, c.id DESC LIMIT ? OFFSET ?");
        assertThat(DiscoveryStore.candidatesSql("ALL")).doesNotContain("LIMIT 500");
        assertThat(DiscoveryStore.candidatesSql("KEYWORD_FILTER")).contains("c.filter_origin='KEYWORD_FILTER'");
        assertThat(DiscoveryStore.candidatesSql("RELEVANT")).contains("c.status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY')");
        assertThatThrownBy(() -> DiscoveryStore.candidatesSql("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
}
