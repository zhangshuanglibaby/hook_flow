package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.delivery.application.DeliveryClaimService.ClaimedTask;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.support.MutableClock;
import com.hookflow.support.TestHttpReceiver;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DeliveryRecoveryIntegrationTests {
    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final Instant BASE = Instant.parse("2026-10-29T08:00:00Z");
    private static final LocalDateTime DUE = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static MutableClock testClock;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {
        @Bean @Primary MutableClock recoveryTestClock() {
            return testClock;
        }
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM endpoint");
    }

    @Test
    void exactLeaseBoundaryAndSnapshotBackoffCreateNewAttemptWithoutOverwritingUnknown() {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, 3);
            ClaimedTask old = claim(context, fixture);
            testClock.setInstant(old.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant().minusMillis(1));
            assertFalse(recover(context, fixture));
            assertEquals("STARTED", attempt(jdbc, fixture, 1));
            testClock.advance(java.time.Duration.ofMillis(1));
            LocalDateTime boundary = old.leaseUntil();
            assertTrue(recover(context, fixture));
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 1));
            assertEquals("EXECUTOR_INTERRUPTED", column(jdbc, "error_code", fixture));
            assertEquals("执行器中断，HTTP结果未知", column(jdbc, "error_message", fixture));
            assertNull(column(jdbc, "http_status", fixture));
            assertNull(column(jdbc, "response_excerpt", fixture));
            assertEquals("WAIT_RETRY", status(jdbc, fixture));
            assertEquals("WAIT_RETRY", eventStatus(jdbc, fixture));
            assertEquals(boundary.plusSeconds(10), time(jdbc, "next_run_at", fixture));
            assertNull(time(jdbc, "terminal_at", fixture));
            assertNull(time(jdbc, "lease_until", fixture));
            assertNull(jdbc.queryForObject("SELECT claim_token FROM delivery_task WHERE id = ?",
                    String.class, fixture.taskId()));
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(old.taskId(), 1,
                    old.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertFalse(context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).isPresent());
            testClock.advance(java.time.Duration.ofSeconds(10));
            ClaimedTask second = claim(context, fixture);
            assertEquals(2, second.attemptNo());
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 1));
            assertEquals("STARTED", attempt(jdbc, fixture, 2));
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(old.taskId(), 1,
                    old.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            testClock.setInstant(second.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            assertTrue(recover(context, fixture));
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 2));
            assertEquals(second.leaseUntil().plusSeconds(30), time(jdbc, "next_run_at", fixture));
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
        }
    }

    @Test
    void disabledEndpointPausesWithSavedDueAndExhaustionBecomesTerminal() {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture paused = fixture(context, jdbc, 3);
            ClaimedTask first = claim(context, paused);
            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", paused.endpointId());
            testClock.setInstant(first.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            assertTrue(recover(context, paused));
            assertEquals("PAUSED", status(jdbc, paused));
            assertEquals("PAUSED", eventStatus(jdbc, paused));
            assertEquals("WAIT_RETRY", jdbc.queryForObject(
                    "SELECT resume_status FROM delivery_task WHERE id = ?", String.class, paused.taskId()));
            assertEquals(first.leaseUntil().plusSeconds(10), time(jdbc, "resume_due_at", paused));
            assertNull(time(jdbc, "next_run_at", paused));
            context.getBean(DueDeliveryScanner.class).scanDueTasks();
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, paused.taskId()));

            Fixture exhausted = fixture(context, jdbc, 1);
            ClaimedTask only = claim(context, exhausted);
            testClock.setInstant(only.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            assertTrue(recover(context, exhausted));
            assertEquals("FINAL_FAILED", status(jdbc, exhausted));
            assertEquals("FINAL_FAILED", eventStatus(jdbc, exhausted));
            assertEquals("UNKNOWN", attempt(jdbc, exhausted, 1));
            assertEquals(only.leaseUntil(), time(jdbc, "finished_at", exhausted));
            assertEquals(only.leaseUntil(), time(jdbc, "terminal_at", exhausted));
            assertNull(time(jdbc, "resume_due_at", exhausted));
        }
    }

    @Test
    void changedTokenRoundStatusOrEndedAttemptAreSafelySkipped() {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture token = fixture(context, jdbc, 3);
            ClaimedTask tokenClaim = claim(context, token);
            Fixture round = fixture(context, jdbc, 3);
            claim(context, round);
            Fixture status = fixture(context, jdbc, 3);
            claim(context, status);
            Fixture ended = fixture(context, jdbc, 3);
            claim(context, ended);
            testClock.advance(java.time.Duration.ofMinutes(2));
            jdbc.update("UPDATE delivery_task SET claim_token = ? WHERE id = ?",
                    UUID.randomUUID().toString(), token.taskId());
            jdbc.update("UPDATE event SET latest_round_no = 2 WHERE id = ?", round.eventPk());
            jdbc.update("UPDATE event SET current_status = 'WAIT_RETRY' WHERE id = ?", status.eventPk());
            jdbc.update("UPDATE delivery_attempt SET result = 'UNKNOWN', ended_at = started_at, "
                    + "duration_ms = 0 WHERE task_id = ?", ended.taskId());
            for (Fixture fixture : new Fixture[] {round, status, ended}) {
                assertFalse(recover(context, fixture));
                assertEquals("DELIVERING", status(jdbc, fixture));
            }
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(tokenClaim.taskId(), 1,
                    tokenClaim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("STARTED", attempt(jdbc, token, 1));
            assertFalse(context.getBean(DeliveryRecoveryService.class).recover(
                    token.taskId(), tokenClaim.claimToken()));
            assertEquals("DELIVERING", status(jdbc, token));
            assertEquals("UNKNOWN", attempt(jdbc, ended, 1));
        }
    }

    @Test
    void legitimateCompletionAndRecoverySerializeOnDatabaseLocks() throws Exception {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, 3);
            ClaimedTask claim = claim(context, fixture);
            testClock.setInstant(claim.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant().minusMillis(1));
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var holder = workers.submit(() -> transaction(context).execute(tx -> {
                    jdbc.queryForObject("SELECT id FROM endpoint WHERE id = ? FOR UPDATE", Long.class,
                            fixture.endpointId());
                    locked.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    return null;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var completion = workers.submit(() -> context.getBean(DeliveryCompletionService.class).complete(
                        claim.taskId(), 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertTrue(completion.get(10, TimeUnit.SECONDS));
            }
            testClock.advance(java.time.Duration.ofMillis(1));
            assertFalse(recover(context, fixture));
            assertEquals("SUCCESS", status(jdbc, fixture));
            assertEquals("SUCCESS", attempt(jdbc, fixture, 1));
        }
    }

    @Test
    void eventWriteFailureRollsBackAttemptAndTaskTogether() {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, 1);
            ClaimedTask claim = claim(context, fixture);
            testClock.setInstant(claim.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            jdbc.execute("ALTER TABLE delivery_task ADD CONSTRAINT ck_recovery_test "
                    + "CHECK (status IN ('PENDING', 'DELIVERING'))");
            try {
                assertThrows(Exception.class, () -> recover(context, fixture));
                assertEquals("STARTED", attempt(jdbc, fixture, 1));
                assertEquals("DELIVERING", status(jdbc, fixture));
                assertEquals("DELIVERING", eventStatus(jdbc, fixture));
                assertEquals(claim.claimToken(), jdbc.queryForObject(
                        "SELECT claim_token FROM delivery_task WHERE id = ?", String.class, fixture.taskId()));
            } finally {
                jdbc.execute("ALTER TABLE delivery_task DROP CHECK ck_recovery_test");
            }
            assertTrue(recover(context, fixture));
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 1));
        }
    }

    @Test
    void recoveredRetryIsClaimedByNormalScannerAsIndependentStartedAttempt() throws Exception {
        testClock = new MutableClock(BASE);
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                var context = start(true, receiver.simulatorUri().getPort())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, 3);
            ClaimedTask first = claim(context, fixture);
            testClock.setInstant(first.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            assertTrue(recover(context, fixture));
            assertEquals("WAIT_RETRY", status(jdbc, fixture));
            testClock.advance(Duration.ofSeconds(10));
            context.getBean(DueDeliveryScanner.class).scanDueTasks();
            assertTrue(receiver.awaitRequest(Duration.ofSeconds(5)) != null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCESS".equals(status(jdbc, fixture)) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("SUCCESS", status(jdbc, fixture));
            assertEquals("SUCCESS", eventStatus(jdbc, fixture));
            assertEquals(List.of("UNKNOWN", "SUCCESS"), jdbc.query(
                    "SELECT result FROM delivery_attempt WHERE task_id = ? ORDER BY attempt_no",
                    (rs, row) -> rs.getString(1), fixture.taskId()));
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(first.taskId(), 1,
                    first.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "late")));
            assertEquals("SUCCESS", attempt(jdbc, fixture, 2));
        }
    }

    @Test
    void scheduledScanFindsExpiredCandidatesButDoesNotChangeValidLeaseOrPausedTask() {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture expired = fixture(context, jdbc, 1);
            Fixture valid = fixture(context, jdbc, 1);
            Fixture paused = fixture(context, jdbc, 3);
            ClaimedTask old = claim(context, expired);
            ClaimedTask active = claim(context, valid);
            ClaimedTask pausedClaim = claim(context, paused);
            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", paused.endpointId());
            testClock.setInstant(old.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            jdbc.update("UPDATE delivery_task SET lease_until = ? WHERE id = ?",
                    active.leaseUntil().plusSeconds(1), valid.taskId());
            jdbc.update("UPDATE delivery_task SET lease_until = ? WHERE id = ?",
                    old.leaseUntil().minusNanos(1_000_000), expired.taskId());
            context.getBean(ExpiredDeliveryRecovery.class).recoverExpiredTasks();
            assertEquals("FINAL_FAILED", status(jdbc, expired));
            assertEquals("DELIVERING", status(jdbc, valid));
            assertEquals("STARTED", attempt(jdbc, valid, 1));
            assertEquals("PAUSED", status(jdbc, paused));
            assertEquals("UNKNOWN", attempt(jdbc, paused, 1));
            assertEquals(pausedClaim.leaseUntil().plusSeconds(10), time(jdbc, "resume_due_at", paused));
            context.getBean(ExpiredDeliveryRecovery.class).recoverExpiredTasks();
            assertEquals("STARTED", attempt(jdbc, valid, 1));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, expired.taskId()));
        }
    }

    @Test
    void recoveryWinsAgainstLateCompletionWithRealRowLocks() throws Exception {
        testClock = new MutableClock(BASE);
        try (var context = start(true)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, 1);
            ClaimedTask claim = claim(context, fixture);
            testClock.setInstant(claim.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var holder = workers.submit(() -> transaction(context).execute(tx -> {
                    jdbc.queryForObject("SELECT id FROM endpoint WHERE id = ? FOR UPDATE", Long.class,
                            fixture.endpointId());
                    locked.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    return null;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var recovering = workers.submit(() -> recover(context, fixture));
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertTrue(recovering.get(10, TimeUnit.SECONDS));
            }
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(claim.taskId(), 1,
                    claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 1));
            assertEquals("FINAL_FAILED", status(jdbc, fixture));
            assertEquals("FINAL_FAILED", eventStatus(jdbc, fixture));
        }
    }

    @Test
    void applicationRestartRecoversPersistedLeaseAtReadyWithoutWaitingForScheduledScan() {
        testClock = new MutableClock(BASE);
        Fixture fixture;
        try (var first = start(true)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            fixture = fixture(first, jdbc, 1);
            ClaimedTask claim = claim(first, fixture);
            testClock.setInstant(claim.leaseUntil().atOffset(java.time.ZoneOffset.UTC).toInstant());
        }
        long started = System.nanoTime();
        try (var restarted = start(false)) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 60);
            assertEquals("FINAL_FAILED", status(jdbc, fixture));
            assertEquals("UNKNOWN", attempt(jdbc, fixture, 1));
            assertEquals("FINAL_FAILED", eventStatus(jdbc, fixture));
        }
    }

    private static Fixture fixture(ConfigurableApplicationContext context, JdbcTemplate jdbc, int maxAttempts) {
        long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'recovery-test-admin'", Long.class);
        long endpoint = context.getBean(EndpointSecretProvisioningService.class).createSimulator(
                "recovery-" + UUID.randomUUID(), 5000, true, actor, UUID.randomUUID().toString()).id();
        String eventId = "recovery-" + UUID.randomUUID();
        jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                + "endpoint_id, latest_round_no, current_status, created_at) VALUES "
                + "(?, 'order.paid', 'null', UNHEX(SHA2('null',256)), ?, 1, 'PENDING', ?)", eventId, endpoint, DUE);
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
        jdbc.update("INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                + "target_type_snapshot, timeout_ms_snapshot, secret_ciphertext_snapshot, "
                + "created_at, updated_at) SELECT ?, 1, 'PENDING', 0, ?, 10, 30, ?, "
                + "'SIMULATOR', 5000, signing_secret_ciphertext, ?, ? FROM endpoint WHERE id = ?",
                eventPk, maxAttempts, DUE, DUE, DUE, endpoint);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, eventPk);
        return new Fixture(endpoint, eventPk, task);
    }

    private static ClaimedTask claim(ConfigurableApplicationContext context, Fixture fixture) {
        return context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).orElseThrow();
    }
    private static boolean recover(ConfigurableApplicationContext context, Fixture fixture) {
        return context.getBean(DeliveryRecoveryService.class).recover(fixture.taskId(),
                context.getBean(JdbcTemplate.class).queryForObject(
                        "SELECT claim_token FROM delivery_task WHERE id = ?", String.class, fixture.taskId()));
    }
    private static String status(JdbcTemplate jdbc, Fixture fixture) {
        return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, fixture.taskId());
    }
    private static String eventStatus(JdbcTemplate jdbc, Fixture fixture) {
        return jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?", String.class, fixture.eventPk());
    }
    private static String attempt(JdbcTemplate jdbc, Fixture fixture, int number) {
        return jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id = ? AND attempt_no = ?",
                String.class, fixture.taskId(), number);
    }
    private static Object column(JdbcTemplate jdbc, String column, Fixture fixture) {
        return jdbc.queryForObject("SELECT " + column + " FROM delivery_attempt WHERE task_id = ? AND attempt_no = 1",
                Object.class, fixture.taskId());
    }
    private static LocalDateTime time(JdbcTemplate jdbc, String field, Fixture fixture) {
        String table = "terminal_at".equals(field) ? "event" : "delivery_task";
        long id = "event".equals(table) ? fixture.eventPk() : fixture.taskId();
        return jdbc.queryForObject("SELECT " + field + " FROM " + table + " WHERE id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), id);
    }
    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        TransactionTemplate template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
    private static ConfigurableApplicationContext start(boolean suppressScheduling) {
        return start(suppressScheduling, 0);
    }
    private static ConfigurableApplicationContext start(boolean suppressScheduling, int port) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var builder = new SpringApplicationBuilder(HookflowBackendApplication.class, ClockOverride.class);
        if (suppressScheduling) builder.profiles("p2-login-test");
        return builder.run("--server.port=" + port, "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=recovery-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=recovery-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
    private record Fixture(long endpointId, long eventPk, long taskId) { }
}
