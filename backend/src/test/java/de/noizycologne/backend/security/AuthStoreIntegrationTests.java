package de.noizycologne.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"noizy.auth.service-secret=fixture-auth-store-key-not-a-production-secret", "noizy.test.trusted-defaults=false", "noizy.discovery.enabled=false", "noizy.discovery.monitor.enabled=false", "logging.level.root=WARN", "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthStoreIntegrationTests {
    private static final String KEY = "fixture-auth-store-key-not-a-production-secret";
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    private final List<String> owned = new ArrayList<>();
    private final List<String> prefixes = new ArrayList<>();
    private String key() {
        String value = UUID.randomUUID().toString().replace("-", "").repeat(2);
        owned.add(value);
        return value;
    }
    private AuthSessionStore store(Instant now) { return new AuthSessionStore(jdbc, manager, Clock.fixed(now, ZoneOffset.UTC)); }
    @AfterEach void cleanup() {
        for (String prefix : prefixes) {
            jdbc.update("DELETE FROM auth_sessions WHERE nonce_hash LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM auth_login_buckets WHERE bucket_hash LIKE ?", prefix + "%");
        }
        for (String id : owned) {
            jdbc.update("DELETE FROM auth_sessions WHERE nonce_hash=?", id);
            jdbc.update("DELETE FROM auth_login_buckets WHERE bucket_hash=?", id);
        }
    }
    @Test void revokedSessionStaysRevokedInSecondInstanceAndCannotBeRegisteredAgain() {
        String nonce = key(), generation = key();
        store(NOW).register(nonce, generation, NOW.plusSeconds(3600).getEpochSecond());
        assertThat(store(NOW).validate(nonce, generation)).isTrue();
        assertThat(store(NOW).validate(nonce, key())).isFalse();
        store(NOW).revoke(nonce);
        assertThat(store(NOW).validate(nonce, generation)).isFalse();
        assertThatThrownBy(() -> store(NOW).register(nonce, generation, NOW.plusSeconds(3600).getEpochSecond()))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(store(NOW).validate(nonce, generation)).isFalse();
    }
    @Test void expiryAndMaximumTtlAreEnforced() {
        String nonce = key(), generation = key();
        assertThatThrownBy(() -> store(NOW).register(nonce, generation, NOW.plusSeconds(30000).getEpochSecond()))
            .isInstanceOf(ResponseStatusException.class);
        store(NOW).register(nonce, generation, NOW.plusSeconds(10).getEpochSecond());
        assertThat(store(NOW.plusSeconds(10)).validate(nonce, generation)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE nonce_hash=?", Long.class, nonce)).isZero();
    }
    @Test void ipAndConfiguredAccountLimitsAreSharedAndExpire() {
        String account = key(), ip = key();
        for (int index=0; index<5; index++) assertThat(store(NOW).attempt(account, ip).allowed()).isTrue();
        assertThat(store(NOW).attempt(account, ip)).isEqualTo(new AuthSessionStore.Attempt(false, 900));
        for (int index=0; index<20; index++) assertThat(store(NOW).attempt(account, key()).allowed()).isTrue();
        assertThat(store(NOW).attempt(account, key()).allowed()).isFalse();
        assertThat(store(NOW.plusSeconds(900)).attempt(account, ip).allowed()).isTrue();
        store(NOW.plusSeconds(900)).clear(account, ip);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_login_buckets WHERE bucket_hash IN (?,?)", Long.class, account, ip)).isZero();
    }
    @Test void parallelRequestsNeverExceedIpLimitAndBusyFailsClosed() throws Exception {
        String account = key(), ip = key();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int index=0; index<20; index++) jobs.add(() -> {
                try { return store(NOW).attempt(account, ip).allowed(); }
                catch (ResponseStatusException busy) { assertThat(busy.getStatusCode().value()).isEqualTo(429); return false; }
            });
            int allowed = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get()) allowed++;
            assertThat(allowed).isBetween(1, 5);
            while (store(NOW).attempt(account, ip).allowed()) { /* consume remaining bounded attempts */ }
            assertThat(store(NOW).attempt(account, ip).allowed()).isFalse();
        }
    }
    @Test void hardCapacitiesRefuseNewStateAndExpiredRowsReleaseCapacity() {
        String prefix = key().substring(0, 32);
        prefixes.add(prefix);
        String generation = key();
        jdbc.update("INSERT INTO auth_sessions(nonce_hash,generation,expires_at) SELECT ? || md5(i::text),?,? FROM generate_series(1,?) i",
            prefix, generation, java.sql.Timestamp.from(NOW.plusSeconds(100)), AuthSessionStore.SESSION_CAPACITY);
        String nonce = key();
        assertThatThrownBy(() -> store(NOW).register(nonce, generation, NOW.plusSeconds(100).getEpochSecond()))
            .isInstanceOf(ResponseStatusException.class);
        store(NOW.plusSeconds(100)).register(nonce, generation, NOW.plusSeconds(200).getEpochSecond());
        jdbc.update("INSERT INTO auth_login_buckets(bucket_hash,attempts,expires_at) SELECT ? || md5(i::text),1,? FROM generate_series(1,?) i",
            prefix, java.sql.Timestamp.from(NOW.plusSeconds(100)), AuthSessionStore.BUCKET_CAPACITY);
        String account = key(), ip = key();
        assertThat(store(NOW).attempt(account, ip).allowed()).isFalse();
        assertThat(store(NOW.plusSeconds(100)).attempt(account, ip).allowed()).isTrue();
    }
    @Test void missingConfigurationKeepsStoreDisabled() {
        var disabled = new AuthStoreController(store(NOW), "");
        var session = new AuthStoreController.Session(key(), key(), NOW.plusSeconds(60).getEpochSecond());
        assertThatThrownBy(() -> disabled.validate(null, session)).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }
    @Test void endpointsRequireDedicatedKeyEvenWithoutApiBoundaryAndNeverCache() throws Exception {
        String body = "{\"nonceHash\":\""+key()+"\",\"generation\":\""+key()+"\",\"expiresAt\":0}";
        mvc.perform(requireNonNull(post("/internal/auth/validate").contentType("application/json").content(body))).andExpect(status().isForbidden());
        mvc.perform(requireNonNull(post("/internal/auth/validate").header("X-Noizy-Auth-Key", "wrong").contentType("application/json").content(body))).andExpect(status().isForbidden());
        mvc.perform(requireNonNull(post("/internal/auth/validate").header("X-Noizy-Auth-Key", KEY).contentType("application/json").content(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(requireNonNull(post("/internal/auth/validate").header("X-Noizy-Auth-Key", KEY).contentType("application/json").content("{\"nonceHash\":\"broken\",\"generation\":\"bad\"}"))).andExpect(status().isBadRequest());
    }
}
