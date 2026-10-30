package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.DeliveryAttemptCountRepository;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointEnableService;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.application.EventStatisticsQueryService;
import com.hookflow.event.application.EventStatisticsQueryService.Counts;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.event.infrastructure.EventStatisticsRepository;
import com.hookflow.support.MutableClock;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class OverviewQueryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final String PATH = "/api/v1/admin/overview";
    static final Instant NOW = Instant.parse("2026-10-30T04:00:00.123Z");
    static final Set<String> STATES = Set.of("PENDING", "DELIVERING", "WAIT_RETRY", "PAUSED", "SUCCESS", "FINAL_FAILED");
    static final String PAYLOAD = "p903-payload-never-return-or-log";
    static final String EXCERPT = "p903-response-excerpt-never-return-or-log";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock overviewClock() { return new MutableClock(NOW); }
        @Bean Probe overviewProbe() { return new Probe(); }
        @Bean @Primary ObservedEvents observedEventStatistics(JdbcTemplate jdbc, Probe probe) { return new ObservedEvents(jdbc, probe); }
        @Bean @Primary ObservedAttempts observedAttemptCount(JdbcTemplate jdbc, Probe probe) { return new ObservedAttempts(jdbc, probe); }
    }

    static class Probe {
        final ThreadLocal<Long> connection = new ThreadLocal<>();
        final AtomicReference<Runnable> afterEvents = new AtomicReference<>();
        void check(JdbcTemplate jdbc) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        }
        AtomicReference<Runnable> hook() { return afterEvents; }
    }
    static class ObservedEvents extends EventStatisticsRepository {
        final JdbcTemplate jdbc;
        final Probe probe;
        ObservedEvents(JdbcTemplate jdbc, Probe probe) { super(jdbc); this.jdbc = jdbc; this.probe = probe; }
        @Override public Counts count(LocalDateTime from, LocalDateTime to) {
            probe.check(jdbc);
            probe.connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            var result = super.count(from, to);
            var hook = probe.afterEvents.getAndSet(null);
            if (hook != null) hook.run();
            return result;
        }
    }
    static class ObservedAttempts extends DeliveryAttemptCountRepository {
        final JdbcTemplate jdbc;
        final Probe probe;
        ObservedAttempts(JdbcTemplate jdbc, Probe probe) { super(jdbc); this.jdbc = jdbc; this.probe = probe; }
        @Override public long count(LocalDateTime from, LocalDateTime to) {
            probe.check(jdbc);
            assertEquals(probe.connection.get(), jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            probe.connection.remove();
            return super.count(from, to);
        }
    }

    @AfterEach void clear() {
        var jdbc = independent();
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config", "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint"))
            jdbc.update("DELETE FROM " + table);
    }

    @Test void emptyDatabaseAndFormalAuthenticationRespectAdministratorBoundary() throws Exception {
        try (var f = new Fixture()) {
            f.assertOverview(f.overview(), Map.of(), null, 0);
            f.error(f.request("GET", f.path(NOW, NOW.plusSeconds(60)), null, null, null), 401);
            f.error(f.request("GET", f.path(NOW, NOW.plusSeconds(60)), null, f.business, null), 403);
            assertEquals(1, f.count("audit_log"));
        }
    }

    @Test void requiredStrictUtcAndInvalidRangesAre400ThroughRealHttp() throws Exception {
        try (var f = new Fixture()) {
            for (String query : List.of("", "?from=" + NOW, "?to=" + NOW, "?from=&to=" + NOW.plusSeconds(1),
                    "?from=" + NOW + "&to=", "?from=" + enc(" ") + "&to=" + NOW.plusSeconds(1),
                    "?from=2026-10-30&to=" + NOW, "?from=2026-10-30t00:00:00Z&to=" + NOW,
                    "?from=2026-10-30T00:00:00z&to=" + NOW,
                    "?from=" + enc("2026-10-30T00:00:00+00:00") + "&to=" + NOW,
                    "?from=" + enc("2026-10-30T08:00:00+08:00") + "&to=" + NOW,
                    "?from=2026-02-29T00:00:00Z&to=" + NOW,
                    "?from=2026-10-30T00:00:00.Z&to=" + NOW,
                    "?from=2026-10-30T00:00:00.1234567890Z&to=" + NOW,
                    "?from=" + NOW + "&to=" + NOW, "?from=" + NOW.plusNanos(1) + "&to=" + NOW))
                f.error(f.get(PATH + query), 400);
            assertEquals(1, f.count("audit_log"));
        }
    }

    @Test void sixLatestStatusesAndTerminalOnlyRateComeFromFormalTransitions() throws Exception {
        try (var f = new Fixture()) {
            long a = f.endpoint(5000), b = f.endpoint(5000);
            f.event("pending", a); f.event("delivering", a); f.claim("delivering");
            f.event("retry", a); f.finish("retry", SingleDeliveryHttpResult.fromHttpResponse(500, EXCERPT));
            f.event("success", a); f.finish("success", SingleDeliveryHttpResult.fromHttpResponse(204, ""));
            f.event("final", a); f.finish("final", SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT));
            f.event("paused", b); f.disable(b);
            var expected = new HashMap<String, Long>(); STATES.forEach(state -> expected.put(state, 1L));
            var before = f.snapshot();
            f.assertOverview(f.overview(), expected, "0.5000000000", 4);
            assertEquals(before, f.snapshot()); f.assertDatabase(NOW, NOW.plusSeconds(600));
        }
    }

    @Test void eventAndAttemptHalfOpenMillisecondAndSubmillisecondBoundariesAreIndependent() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000);
            f.clock.setInstant(NOW.minusMillis(1)); f.event("before", endpoint); f.claim("before");
            f.clock.setInstant(NOW); f.event("at-from", endpoint); f.claim("at-from");
            f.clock.setInstant(NOW.plusMillis(1)); f.event("at-to", endpoint); f.claim("at-to");
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("DELIVERING", 1L), null, 1);
            f.assertOverview(f.overview(NOW.plusNanos(1), NOW.plusMillis(1).plusNanos(1)), Map.of("DELIVERING", 1L), null, 1);
            f.assertOverview(f.overview(NOW.plusNanos(1), NOW.plusNanos(999_999)), Map.of(), null, 0);
            f.assertOverview(f.overview(NOW.minusMillis(1).plusNanos(1), NOW.plusNanos(1)), Map.of("DELIVERING", 1L), null, 1);
            f.assertDatabase(NOW, NOW.plusMillis(1));
        }
    }

    @Test void crossingEventAndAttemptTimesCanYieldZeroEventsButPositiveAttemptsOrTheReverse() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000);
            f.clock.setInstant(NOW.minusSeconds(1)); f.event("old-event", endpoint);
            f.clock.setInstant(NOW); var oldClaim = f.claim("old-event");
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of(), null, 1);
            f.event("in-range-event", endpoint);
            f.clock.setInstant(NOW.plusSeconds(1)); f.claim("in-range-event");
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("DELIVERING", 1L), null, 1);
            f.assertOverview(f.overview(NOW.minusSeconds(1), NOW.minusSeconds(1).plusMillis(1)), Map.of("DELIVERING", 1L), null, 0);
            f.complete(oldClaim, SingleDeliveryHttpResult.unknown());
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("DELIVERING", 1L), null, 1);
            f.assertDatabase(NOW, NOW.plusMillis(1));
        }
    }

    @Test void historicalRangeTracksNewRoundWithoutNewEventAndRetainsEveryAttemptResult() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000);
            f.event("history", endpoint);
            var first = f.claim("history");
            f.assertOverview(f.overview(), Map.of("DELIVERING", 1L), null, 1);
            f.complete(first, SingleDeliveryHttpResult.fromHttpResponse(500, EXCERPT));
            f.due("history"); f.finish("history", SingleDeliveryHttpResult.unknown());
            f.due("history"); f.finish("history", SingleDeliveryHttpResult.localFailure());
            long oldTask = f.task("history"); var oldAttempts = f.attempts(oldTask);
            f.assertOverview(f.overview(), Map.of("FINAL_FAILED", 1L), "0.0000000000", 3);
            f.clock.setInstant(NOW.plusSeconds(60)); f.redeliver("history");
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("PENDING", 1L), null, 1);
            f.assertOverview(f.overview(), Map.of("PENDING", 1L), null, 3);
            var next = f.claim("history");
            f.assertOverview(f.overview(), Map.of("DELIVERING", 1L), null, 4);
            assertEquals("STARTED", f.attempts(next.taskId()).getFirst().get("result"));
            f.complete(next, SingleDeliveryHttpResult.fromHttpResponse(200, "ok"));
            f.assertOverview(f.overview(), Map.of("SUCCESS", 1L), "1.0000000000", 4);
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("SUCCESS", 1L), "1.0000000000", 1);
            f.assertOverview(f.overview(NOW.plusSeconds(60), NOW.plusSeconds(60).plusMillis(1)), Map.of(), null, 1);
            assertEquals(oldAttempts, f.attempts(oldTask)); assertEquals(1, f.count("event"));
            assertEquals(Set.of("HTTP_FAILURE", "UNKNOWN", "LOCAL_FAILURE", "SUCCESS"), new HashSet<>(f.jdbc.queryForList("SELECT result FROM delivery_attempt", String.class)));
            f.assertDatabase(NOW, NOW.plusSeconds(600));
        }
    }

    @Test void emptyRangesNonterminalOnlySuccessOnlyFailureOnlyAndRecurringRateAreCorrect() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000); f.event("pending", endpoint);
            f.assertOverview(f.overview(), Map.of("PENDING", 1L), null, 0);
            f.finish("pending", SingleDeliveryHttpResult.fromHttpResponse(200, "ok"));
            f.assertOverview(f.overview(), Map.of("SUCCESS", 1L), "1.0000000000", 1);
            f.clock.advance(Duration.ofSeconds(1)); f.event("failed", endpoint); f.finish("failed", SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT));
            f.assertOverview(f.overview(NOW.plusSeconds(1), NOW.plusSeconds(2)), Map.of("FINAL_FAILED", 1L), "0.0000000000", 1);
            f.event("failed-2", endpoint); f.finish("failed-2", SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT));
            f.assertOverview(f.overview(), Map.of("SUCCESS", 1L, "FINAL_FAILED", 2L), "0.3333333333", 3);
            f.event("success-2", endpoint); f.finish("success-2", SingleDeliveryHttpResult.fromHttpResponse(200, "ok"));
            f.event("success-3", endpoint); f.finish("success-3", SingleDeliveryHttpResult.fromHttpResponse(200, "ok"));
            f.event("success-4", endpoint); f.finish("success-4", SingleDeliveryHttpResult.fromHttpResponse(200, "ok"));
            f.assertOverview(f.overview(), Map.of("SUCCESS", 4L, "FINAL_FAILED", 2L), "0.6666666667", 6);
            f.assertOverview(f.overview(NOW.plusSeconds(2), NOW.plusSeconds(3)), Map.of(), null, 0);
        }
    }

    @Test void aggregateApplicationCapabilitiesAreMandatoryAndJoinSameReadOnlyTransaction() throws Exception {
        try (var f = new Fixture()) {
            var events = f.context.getBean(EventStatisticsQueryService.class);
            var attempts = f.context.getBean(DeliveryAttemptCountQueryService.class);
            var from = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC); var to = from.plusSeconds(60);
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> events.count(from, to));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> attempts.count(from, to));
            var tx = f.tx(); tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.executeWithoutResult(status -> {
                long connection = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertEquals(0, events.count(from, to).total()); assertEquals(0, attempts.count(from, to));
                assertEquals(connection, f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            });
        }
    }

    @Test void allFourMetricsShareSnapshotAcrossRealCreationClaimCompletionPauseResumeRecoveryAndNewRoundCommits() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000);
            f.between(() -> f.tx().executeWithoutResult(status -> { f.create("snapshot", endpoint); f.claim("snapshot"); }));
            var original = f.currentClaim("snapshot");
            f.between(() -> f.complete(original, SingleDeliveryHttpResult.fromHttpResponse(500, EXCERPT)));
            f.between(() -> f.disable(endpoint));
            f.between(() -> f.enable(endpoint));
            f.due("snapshot");
            f.between(() -> f.claim("snapshot"));
            var second = f.currentClaim("snapshot"); f.clock.setInstant(second.leaseUntil().toInstant(ZoneOffset.UTC));
            f.between(() -> assertTrue(f.context.getBean(DeliveryRecoveryService.class).recover(second.taskId(), second.claimToken())));
            f.due("snapshot"); var third = f.claim("snapshot");
            f.between(() -> f.complete(third, SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT)));
            f.between(() -> f.tx().executeWithoutResult(status -> {
                f.context.getBean(DeliveryRedeliveryService.class).redeliver("snapshot", f.actor, UUID.randomUUID().toString());
                f.claim("snapshot");
            }));
            f.assertOverview(f.overview(), Map.of("DELIVERING", 1L), null, 4);
        }
    }

    @Test void httpOverviewReturnsOldCommittedSnapshotBeforeRealWriterReleasesItsRowLocks() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000);
            f.whileUncommitted("locked", () -> { f.create("locked", endpoint); f.claim("locked"); });
            var first = f.currentClaim("locked");
            f.whileUncommitted("locked", () -> f.complete(first, SingleDeliveryHttpResult.fromHttpResponse(500, EXCERPT)));
            f.whileUncommitted("locked", () -> f.disable(endpoint));
            f.whileUncommitted("locked", () -> f.enable(endpoint));
            f.due("locked"); f.whileUncommitted("locked", () -> f.claim("locked"));
            var second = f.currentClaim("locked"); f.clock.setInstant(second.leaseUntil().toInstant(ZoneOffset.UTC));
            f.whileUncommitted("locked", () -> assertTrue(f.context.getBean(DeliveryRecoveryService.class).recover(second.taskId(), second.claimToken())));
            f.due("locked"); f.finish("locked", SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT));
            f.whileUncommitted("locked", () -> {
                f.context.getBean(DeliveryRedeliveryService.class).redeliver("locked", f.actor, UUID.randomUUID().toString());
                f.claim("locked");
            });
            f.assertOverview(f.overview(), Map.of("DELIVERING", 1L), null, 4);
        }
    }

    @Test void formalAdminEventUsesRealScannerExecutorClientAndP8ThenManualNewRoundChangesHistoricalRate() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000); f.mode("ALWAYS_500");
            var response = f.request("POST", "/api/v1/admin/events/test", f.token, null,
                    "{\"eventType\":\"overview.e2e\",\"endpointId\":" + endpoint + ",\"payload\":{\"fictional\":true}}");
            assertEquals(202, response.statusCode()); String id = f.json(response).path("eventId").asString(); UUID.fromString(id);
            long old = f.task(id);
            for (int attempt = 1; attempt <= 3; attempt++) {
                f.scan(); f.awaitStatus(id, attempt == 3 ? "FINAL_FAILED" : "WAIT_RETRY");
                if (attempt < 3) f.due(id);
            }
            var history = f.attempts(old);
            assertEquals(3, history.size()); history.forEach(row -> { assertEquals("HTTP_FAILURE", row.get("result")); assertEquals(500, ((Number) row.get("http_status")).intValue()); });
            f.assertOverview(f.overview(), Map.of("FINAL_FAILED", 1L), "0.0000000000", 3);
            f.mode("ALWAYS_200"); f.clock.setInstant(NOW.plusSeconds(90)); f.redeliver(id);
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("PENDING", 1L), null, 1);
            f.scan(); f.awaitStatus(id, "SUCCESS");
            f.assertOverview(f.overview(), Map.of("SUCCESS", 1L), "1.0000000000", 4);
            f.assertOverview(f.overview(NOW, NOW.plusMillis(1)), Map.of("SUCCESS", 1L), "1.0000000000", 1);
            assertEquals(history, f.attempts(old)); assertEquals(1, f.count("event")); assertEquals(2, f.count("delivery_task"));
            assertEquals(4, f.jdbc.queryForObject("SELECT COUNT(*) FROM simulator_request_log WHERE event_id=?", Long.class, id));
            assertEquals(2, f.jdbc.queryForObject("SELECT latest_round_no FROM event WHERE event_id=?", Integer.class, id));
            assertNotNull(f.jdbc.queryForObject("SELECT terminal_at FROM event WHERE event_id=?", LocalDateTime.class, id));
            f.assertDatabase(NOW, NOW.plusSeconds(600));
        }
    }

    @Test void plannedSimulator200DoesNotCountUnknownAsSuccessAndEndingStartedDoesNotDoubleCount() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(1200);
            assertEquals(200, f.request("PUT", "/api/v1/admin/simulator/config", f.token, null, "{\"mode\":\"PROCESSED_THEN_DELAY\",\"delayMs\":3500}").statusCode());
            f.event("unknown", endpoint); f.scan(); f.awaitStatus("unknown", "WAIT_RETRY");
            var first = f.attempts(f.task("unknown")).getFirst(); assertEquals("UNKNOWN", first.get("result")); assertNull(first.get("http_status"));
            assertEquals(200, f.jdbc.queryForObject("SELECT planned_status FROM simulator_request_log WHERE event_id='unknown'", Integer.class));
            assertNotNull(f.jdbc.queryForObject("SELECT processed_at FROM simulator_event_state WHERE event_id='unknown'", LocalDateTime.class));
            f.assertOverview(f.overview(), Map.of("WAIT_RETRY", 1L), null, 1);
            f.due("unknown"); f.scan(); f.awaitStatus("unknown", "SUCCESS");
            f.assertOverview(f.overview(), Map.of("SUCCESS", 1L), "1.0000000000", 2);
            assertEquals(first, f.attempts(f.task("unknown")).getFirst());
        }
    }

    @Test void successfulQueriesAndGenuineDatabaseFailuresHaveNoWritesAuditOrSensitiveLogs() throws Exception {
        try (var f = new Fixture()) {
            long endpoint = f.endpoint(5000); f.event("safe", endpoint); var claim = f.claim("safe"); f.secrets.add(claim.claimToken());
            f.complete(claim, SingleDeliveryHttpResult.fromHttpResponse(404, EXCERPT));
            var logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                var before = f.snapshot(); f.overview(); assertEquals(before, f.snapshot());
                // 仅在临时测试库中重命名表，制造真正的读取故障，finally 恢复。
                for (String table : List.of("event", "delivery_attempt")) {
                    f.jdbc.execute("RENAME TABLE " + table + " TO p903_unavailable");
                    try { f.error(f.get(f.path(NOW, NOW.plusSeconds(600))), 500); }
                    finally { f.jdbc.execute("RENAME TABLE p903_unavailable TO " + table); }
                }
                assertEquals(before, f.snapshot()); f.overview(); assertFalse(logs.list.isEmpty());
                for (var log : logs.list) { f.noSecrets(log.getFormattedMessage()); assertNull(log.getThrowableProxy()); }
            } finally { logger.detachAppender(logs); logs.stop(); }
        }
    }

    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static JdbcTemplate independent() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.setQueryTimeout(3); return jdbc;
    }
    static void await(Supplier<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get()) { if (System.nanoTime() >= end) fail("有界等待超时"); Thread.sleep(10); }
    }
    static void waitLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    static class Fixture implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final JsonMapper mapper = JsonMapper.builder().build();
        final ConfigurableApplicationContext context;
        final JdbcTemplate jdbc;
        final MutableClock clock;
        final String base, token, business, encryption;
        final long actor;
        final List<String> secrets = new ArrayList<>();
        Fixture() throws Exception {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key); encryption = Base64.getEncoder().encodeToString(key);
            context = new SpringApplicationBuilder(HookflowBackendApplication.class, Configuration.class).run(
                    "--spring.profiles.active=p2-login-test", "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p903-admin", "--HOOKFLOW_ADMIN_PASSWORD=p903-admin-password", "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + encryption,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc = context.getBean(JdbcTemplate.class); clock = context.getBean(MutableClock.class);
            base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            var login = request("POST", "/api/v1/auth/login", null, null, "{\"username\":\"p903-admin\",\"password\":\"p903-admin-password\"}");
            assertEquals(200, login.statusCode()); token = json(login).path("token").asString();
            var rotate = request("POST", "/api/v1/admin/api-key/rotate", token, null, null);
            assertEquals(200, rotate.statusCode()); business = json(rotate).path("apiKey").asString();
            secrets.addAll(List.of(token, business, encryption, "p903-admin-password"));
            actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='p903-admin'", Long.class);
        }
        HttpResponse<String> request(String method, String path, String admin, String key, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(7)).header("X-Request-Id", "forged-p903");
            if (admin != null) builder.header("Authorization", "Bearer " + admin);
            if (key != null) builder.header("X-HookFlow-Api-Key", key);
            if (body != null) builder.header("Content-Type", "application/json");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, token, null, null); }
        JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
        String path(Instant from, Instant to) { return PATH + "?from=" + from + "&to=" + to; }
        JsonNode overview() throws Exception { return overview(NOW, NOW.plusSeconds(600)); }
        JsonNode overview(Instant from, Instant to) throws Exception {
            var response = get(path(from, to)); assertEquals(200, response.statusCode(), response.body()); noSecrets(response.body());
            requestId(response); var result = json(response);
            assertEquals(Set.of("eventCount", "statusCounts", "successRate", "attemptCount"), result.propertyNames());
            assertEquals(STATES, result.path("statusCounts").propertyNames());
            long sum = 0; for (String state : STATES) { var count = result.path("statusCounts").path(state); assertTrue(count.isIntegralNumber()); assertTrue(count.asLong() >= 0); sum += count.asLong(); }
            assertEquals(sum, result.path("eventCount").asLong());
            assertTrue(result.path("attemptCount").isIntegralNumber());
            assertTrue(result.path("successRate").isNull() || result.path("successRate").isNumber());
            return result;
        }
        void assertOverview(JsonNode result, Map<String, Long> expected, String rate, long attempts) {
            for (String state : STATES) assertEquals(expected.getOrDefault(state, 0L).longValue(), result.path("statusCounts").path(state).asLong(), state);
            assertEquals(expected.values().stream().mapToLong(Long::longValue).sum(), result.path("eventCount").asLong());
            if (rate == null) assertTrue(result.path("successRate").isNull());
            else assertEquals(0, new BigDecimal(rate).compareTo(result.path("successRate").decimalValue()));
            assertEquals(attempts, result.path("attemptCount").asLong());
        }
        void assertDatabase(Instant from, Instant to) throws Exception {
            var result = overview(from, to);
            var start = LocalDateTime.ofInstant(from, ZoneOffset.UTC); var end = LocalDateTime.ofInstant(to, ZoneOffset.UTC);
            assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE created_at>=? AND created_at<?", Long.class, start, end).longValue(), result.path("eventCount").asLong());
            for (String state : STATES)
                assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE created_at>=? AND created_at<? AND current_status=?", Long.class, start, end, state).longValue(), result.path("statusCounts").path(state).asLong());
            assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE started_at>=? AND started_at<?", Long.class, start, end).longValue(), result.path("attemptCount").asLong());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event e JOIN delivery_task t ON t.event_pk=e.id AND t.round_no=e.latest_round_no WHERE e.current_status<>t.status", Long.class));
        }
        void requestId(HttpResponse<String> response) {
            String id = response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(id).toString(), id); assertNotEquals("forged-p903", id);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?", Long.class, id));
        }
        void noSecrets(String text) {
            for (String secret : secrets) assertFalse(text.contains(secret));
            for (String value : List.of(PAYLOAD, EXCERPT, "payload", "secretCiphertext", "secret_ciphertext", "claimToken", "claim_token", "signingSecret", "token_hash", "key_hash", "p903_unavailable", "SELECT ", "BadSqlGrammarException:"))
                assertFalse(text.contains(value), value);
        }
        void error(HttpResponse<String> response, int status) {
            assertEquals(status, response.statusCode(), response.body()); var result = json(response);
            assertEquals(Set.of("code", "message", "requestId"), result.propertyNames());
            assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), result.path("requestId").asString());
            requestId(response); noSecrets(response.body());
            assertEquals(switch (status) { case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; default -> "INTERNAL_ERROR"; }, result.path("code").asString());
        }
        long endpoint(int timeout) throws Exception {
            var response = request("POST", "/api/v1/admin/endpoints", token, null, "{\"name\":\"p903\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":" + timeout + "}");
            assertEquals(201, response.statusCode()); long id = json(response).path("id").asLong();
            byte[] ciphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs, row) -> rs.getBytes(1), id);
            secrets.add(context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(ciphertext)); secrets.add(Base64.getEncoder().encodeToString(ciphertext)); return id;
        }
        void event(String id, long endpoint) throws Exception {
            var response = request("POST", "/api/v1/events", null, business, "{\"eventId\":" + mapper.writeValueAsString(id) + ",\"eventType\":\"overview.test\",\"endpointId\":" + endpoint + ",\"payload\":{\"marker\":\"" + PAYLOAD + "\"}}");
            assertEquals(202, response.statusCode(), response.body());
        }
        void create(String id, long endpoint) {
            var payload = new EventPayloadCanonicalizer().canonicalize(mapper.readTree("{\"marker\":\"" + PAYLOAD + "\"}"));
            assertTrue(context.getBean(EventCreationService.class).create(id, "overview.test", endpoint, payload).isPresent());
        }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no", Long.class, id); }
        DeliveryClaimService.ClaimedTask claim(String id) { return context.getBean(DeliveryClaimService.class).claim(task(id)).orElseThrow(); }
        DeliveryClaimService.ClaimedTask currentClaim(String id) { return jdbc.queryForObject("SELECT id,attempt_count,claim_token,lease_until FROM delivery_task WHERE id=?", (rs,row) -> new DeliveryClaimService.ClaimedTask(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getObject(4, LocalDateTime.class)), task(id)); }
        void complete(DeliveryClaimService.ClaimedTask claim, SingleDeliveryHttpResult result) { assertTrue(context.getBean(DeliveryCompletionService.class).complete(claim.taskId(), claim.attemptNo(), claim.claimToken(), result)); }
        void finish(String id, SingleDeliveryHttpResult result) { complete(claim(id), result); }
        void due(String id) { clock.setInstant(jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?", LocalDateTime.class, task(id)).toInstant(ZoneOffset.UTC)); }
        void disable(long endpoint) { context.getBean(EndpointDisableService.class).disable(endpoint, actor, UUID.randomUUID().toString()); }
        void enable(long endpoint) { context.getBean(EndpointEnableService.class).enable(endpoint, actor, UUID.randomUUID().toString()); }
        void redeliver(String id) throws Exception { var response = request("POST", "/api/v1/admin/events/" + enc(id) + "/redeliver", token, null, null); assertEquals(202, response.statusCode(), response.body()); }
        void mode(String mode) throws Exception { assertEquals(200, request("PUT", "/api/v1/admin/simulator/config", token, null, "{\"mode\":\"" + mode + "\"}").statusCode()); }
        void scan() { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
        void awaitStatus(String id, String status) throws Exception { await(() -> status.equals(jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?", String.class, id))); }
        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
        List<Map<String,Object>> attempts(long task) { return jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", task); }
        Map<String,Object> snapshot() {
            var state = new LinkedHashMap<String,Object>();
            for (String table : List.of("event", "delivery_task", "delivery_attempt", "endpoint", "audit_log", "simulator_config", "simulator_event_state", "simulator_request_log"))
                state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1").stream().map(row -> { var copy = new LinkedHashMap<>(row); copy.replaceAll((key,value) -> value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value); return copy; }).toList());
            return state;
        }
        TransactionTemplate tx() { var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx; }
        void between(Runnable write) throws Exception {
            var before = overview(); var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
            var slot = context.getBean(Probe.class).hook(); slot.set(() -> { reached.countDown(); waitLatch(release); });
            var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            Future<JsonNode> reading = worker.submit(() -> overview());
            try {
                assertTrue(reached.await(4, TimeUnit.SECONDS)); write.run(); release.countDown();
                assertEquals(before, reading.get(5, TimeUnit.SECONDS)); assertNotEquals(before, overview());
                assertDatabase(NOW, NOW.plusSeconds(600));
            } finally { release.countDown(); slot.set(null); worker.shutdownNow(); assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS)); }
        }
        void whileUncommitted(String id, Runnable write) throws Exception {
            var before = overview(); var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            try {
                tx().executeWithoutResult(status -> {
                    write.run(); long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    long event = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, id);
                    var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())); observer.setQueryTimeout(2);
                    assertTrue(observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_NAME='event' AND l.INDEX_NAME='PRIMARY' AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED' AND l.LOCK_DATA=?", Long.class, connection, Long.toString(event)) > 0);
                    Future<JsonNode> reading = worker.submit(() -> overview());
                    try { assertEquals(before, reading.get(4, TimeUnit.SECONDS)); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                    assertFalse(status.isCompleted());
                });
                assertNotEquals(before, overview()); assertDatabase(NOW, NOW.plusSeconds(600));
            } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS)); }
        }
        @Override public void close() { client.close(); context.close(); }
    }
}
