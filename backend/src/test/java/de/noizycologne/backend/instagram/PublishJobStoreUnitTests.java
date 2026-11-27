package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.store.PublishJob;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishJobStoreUnitTests {
    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    @Test
    void enqueueIsIdempotentBecauseTheInsertAndLookupHappenInOneStatement() {
        StubJdbc jdbc = new StubJdbc(null, null);
        PublishJobStore store = new PublishJobStore(jdbc);

        String id = store.enqueue("key-1", "draft-1", "entry-1", "account-1",
                "venue-1", NOW, "Caption", 1, 1);

        assertThat(id).isEqualTo("existing-job");
        assertThat(jdbc.queries).hasSize(1);
        String insert = jdbc.queries.get(0);
        assertThat(insert).contains("ON CONFLICT DO NOTHING");
        assertThat(insert).contains("idempotency_key");
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void claimingUsesSkipLockedRowsAndReturnsTheLeasedJob() throws SQLException {
        ResultSet idRow = mock(ResultSet.class);
        when(idRow.getString("id")).thenReturn("job-1");
        ResultSet jobRow = mock(ResultSet.class);
        when(jobRow.getString("id")).thenReturn("job-1");
        when(jobRow.getString("draft_id")).thenReturn("draft-1");
        when(jobRow.getString("calendar_entry_id")).thenReturn("entry-1");
        when(jobRow.getString("account_id")).thenReturn("account-1");
        when(jobRow.getString("venue_id")).thenReturn("venue-1");
        when(jobRow.getString("status")).thenReturn("CLAIMED");
        when(jobRow.getString("idempotency_key")).thenReturn("key-1");
        when(jobRow.getString("caption")).thenReturn("Caption");
        when(jobRow.getString("lease_owner")).thenReturn("worker-1");
        when(jobRow.getTimestamp("lease_until")).thenReturn(Timestamp.from(NOW.plus(Duration.ofMinutes(5))));
        when(jobRow.getTimestamp("planned_at")).thenReturn(Timestamp.from(NOW));
        when(jobRow.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        when(jobRow.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
        when(jobRow.getInt("position_in_plan")).thenReturn(1);
        when(jobRow.getLong("plan_revision")).thenReturn(1L);
        when(jobRow.getInt("attempt_count")).thenReturn(1);
        when(jobRow.getBoolean("needs_reconciliation")).thenReturn(false);
        StubJdbc jdbc = new StubJdbc(idRow, jobRow);
        PublishJobStore store = new PublishJobStore(jdbc);

        List<PublishJob> claimed = store.claimDueJobs("worker-1", NOW,
                Duration.ofMinutes(5), 5);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).status()).isEqualTo(PublicationStatus.CLAIMED);
        assertThat(claimed.get(0).leaseOwner()).isEqualTo("worker-1");
        assertThat(claimed.get(0).attemptCount()).isEqualTo(1);
        assertThat(jdbc.queries.get(0)).contains("FOR UPDATE SKIP LOCKED");
        String claim = jdbc.queries.get(1);
        assertThat(claim).contains("attempt_count = attempt_count + 1");
        assertThat(claim).contains("RETURNING *");
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void claimingAnEmptyQueueReturnsNoJob() {
        StubJdbc jdbc = new StubJdbc(null, null);
        PublishJobStore store = new PublishJobStore(jdbc);

        assertThat(store.claimDueJobs("worker-1", NOW, Duration.ofMinutes(5), 5)).isEmpty();
    }

    private static final class StubJdbc extends JdbcTemplate {
        private final ResultSet idRow;
        private final ResultSet jobRow;
        private final List<String> queries = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();

        private StubJdbc(ResultSet idRow, ResultSet jobRow) {
            this.idRow = idRow;
            this.jobRow = jobRow;
        }

        @Override
        public <T> @NonNull List<T> query(@NonNull String sql, @NonNull RowMapper<T> rowMapper,
                                          @Nullable Object @Nullable ... args) {
            queries.add(sql);
            try {
                if (sql.contains("FOR UPDATE SKIP LOCKED")) {
                    return Objects.requireNonNull(idRow == null ? List.<T>of()
                            : List.of(Objects.requireNonNull(rowMapper.mapRow(idRow, 1))));
                }
                if (sql.contains("RETURNING *")) {
                    return Objects.requireNonNull(jobRow == null ? List.<T>of()
                            : List.of(Objects.requireNonNull(rowMapper.mapRow(jobRow, 1))));
                }
                return Objects.requireNonNull(List.<T>of());
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public <T> T queryForObject(@NonNull String sql, @NonNull Class<T> requiredType,
                                    @Nullable Object @Nullable ... args) {
            queries.add(sql);
            return requiredType.cast("existing-job");
        }

        @Override
        public int update(@NonNull String sql, @Nullable Object @Nullable ... args) {
            updates.add(sql);
            return 1;
        }
    }
}
