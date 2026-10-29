package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.delivery.application.DeliveryClaimService.ClaimedTask;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DeliveryClaimServiceIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime DUE = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Test
    void claimsOneDueTaskAndCommitsAllThreeRowsWithoutSendingHttp() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            LocalDateTime before = LocalDateTime.ofInstant(context.getBean(java.time.Clock.class).instant(),
                    ZoneOffset.UTC);
            Optional<ClaimedTask> claim = context.getBean(DeliveryClaimService.class).claim(fixture.taskId());

            assertTrue(claim.isPresent());
            ClaimedTask result = claim.orElseThrow();
            assertEquals(fixture.taskId(), result.taskId());
            assertEquals(1, result.attemptNo());
            assertTrue(result.leaseUntil().isAfter(before.plusSeconds(60)));
            assertTrue(result.leaseUntil().isAfter(before.plusSeconds(35)));
            assertEquals("DELIVERING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("DELIVERING", eventStatus(jdbc, fixture.eventPk()));
            assertEquals(1, jdbc.queryForObject("SELECT latest_round_no FROM event WHERE id = ?",
                    Integer.class, fixture.eventPk()));
            assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
            assertEquals(result.claimToken(), jdbc.queryForObject(
                    "SELECT claim_token FROM delivery_task WHERE id = ?", String.class, fixture.taskId()));
            assertEquals(result.leaseUntil(), jdbc.queryForObject("SELECT lease_until FROM delivery_task WHERE id = ?",
                    (rs, row) -> rs.getObject(1, LocalDateTime.class), fixture.taskId()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ? "
                    + "AND attempt_no = 1 AND result = 'STARTED' AND ended_at IS NULL AND http_status IS NULL",
                    Long.class, fixture.taskId()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ? "
                    + "AND request_id IS NOT NULL AND request_id <> ''", Long.class, fixture.taskId()));
            assertTrue(context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).isEmpty());
            assertEquals(1L, countAttempts(jdbc, fixture.taskId()));
        }
    }

    @Test
    void dueRetryCreatesSecondStartedAttemptWithoutChangingFirstResult() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            jdbc.update("UPDATE event SET current_status = 'WAIT_RETRY' WHERE id = ?", fixture.eventPk());
            jdbc.update("UPDATE delivery_task SET status = 'WAIT_RETRY', attempt_count = 1 WHERE id = ?",
                    fixture.taskId());
            jdbc.update("INSERT INTO delivery_attempt (task_id, attempt_no, result, started_at, "
                            + "ended_at, duration_ms, request_id) VALUES (?, 1, 'HTTP_FAILURE', ?, ?, 100, ?)",
                    fixture.taskId(), DUE.minusMinutes(1), DUE.minusMinutes(1).plusNanos(100_000_000),
                    UUID.randomUUID().toString());

            ClaimedTask claimed = context.getBean(DeliveryClaimService.class)
                    .claim(fixture.taskId()).orElseThrow();
            assertEquals(2, claimed.attemptNo());
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
            assertEquals("DELIVERING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("DELIVERING", eventStatus(jdbc, fixture.eventPk()));
            assertEquals("HTTP_FAILURE", jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ? AND attempt_no = 1",
                    String.class, fixture.taskId()));
            assertEquals("STARTED", jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ? AND attempt_no = 2",
                    String.class, fixture.taskId()));
            assertEquals(2L, countAttempts(jdbc, fixture.taskId()));
        }
    }

    @Test
    void concurrentClaimersPersistOnlyOneFirstAttempt() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            DeliveryClaimService service = context.getBean(DeliveryClaimService.class);
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Optional<ClaimedTask>> first = workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.claim(fixture.taskId());
                });
                Future<Optional<ClaimedTask>> second = workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.claim(fixture.taskId());
                });
                start.countDown();
                int successes = (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                        + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
                assertEquals(1, successes);
            }
            assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
            assertEquals(1L, countAttempts(jdbc, fixture.taskId()));
            assertEquals("DELIVERING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("DELIVERING", eventStatus(jdbc, fixture.eventPk()));
        }
    }

    @Test
    void disabledEndpointAndNotYetDueTaskStayUnclaimed() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DeliveryClaimService service = context.getBean(DeliveryClaimService.class);
            Fixture disabled = fixture(context, jdbc, false, DUE);
            Fixture future = fixture(context, jdbc, true, DUE.plusYears(20));

            assertTrue(service.claim(disabled.taskId()).isEmpty());
            assertTrue(service.claim(future.taskId()).isEmpty());
            assertTrue(service.claim(Long.MAX_VALUE).isEmpty());
            assertEquals(0L, countAttempts(jdbc, disabled.taskId()));
            assertEquals(0L, countAttempts(jdbc, future.taskId()));
            assertEquals("PENDING", taskStatus(jdbc, disabled.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, disabled.eventPk()));
            assertEquals("PENDING", taskStatus(jdbc, future.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, future.eventPk()));
        }
    }

    @Test
    void mismatchedLatestStateAndExhaustedAttemptsAreNotClaimed() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture mismatch = fixture(context, jdbc, true, DUE);
            Fixture exhausted = fixture(context, jdbc, true, DUE);
            jdbc.update("UPDATE event SET current_status = 'WAIT_RETRY' WHERE id = ?", mismatch.eventPk());
            jdbc.update("UPDATE delivery_task SET attempt_count = max_attempts WHERE id = ?", exhausted.taskId());
            DeliveryClaimService service = context.getBean(DeliveryClaimService.class);

            assertTrue(service.claim(mismatch.taskId()).isEmpty());
            assertTrue(service.claim(exhausted.taskId()).isEmpty());
            assertEquals(0L, countAttempts(jdbc, mismatch.taskId()));
            assertEquals(0L, countAttempts(jdbc, exhausted.taskId()));
            assertEquals("PENDING", taskStatus(jdbc, mismatch.taskId()));
            assertEquals("WAIT_RETRY", eventStatus(jdbc, mismatch.eventPk()));
            assertEquals("PENDING", taskStatus(jdbc, exhausted.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, exhausted.eventPk()));
        }
    }

    @Test
    void disableWinningEndpointLockPreventsClaim() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> disable = workers.submit(() -> transaction(context).execute(status -> {
                    jdbc.queryForObject("SELECT id FROM endpoint WHERE id = ? FOR UPDATE", Long.class,
                            fixture.endpointId());
                    jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", fixture.endpointId());
                    locked.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return null;
                }));
                try {
                    assertTrue(locked.await(5, TimeUnit.SECONDS));
                    Future<Optional<ClaimedTask>> claimant = workers.submit(() ->
                            context.getBean(DeliveryClaimService.class).claim(fixture.taskId()));
                    assertThrows(TimeoutException.class, () -> claimant.get(250, TimeUnit.MILLISECONDS));
                    release.countDown();
                    disable.get(5, TimeUnit.SECONDS);
                    assertTrue(claimant.get(5, TimeUnit.SECONDS).isEmpty());
                } finally {
                    release.countDown();
                }
            }
            assertEquals(0L, countAttempts(jdbc, fixture.taskId()));
            assertEquals("PENDING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, fixture.eventPk()));
        }
    }

    @Test
    void changesAreInvisibleBeforeCommitAndRollbackTogether() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = workers.submit(() -> assertThrows(IllegalStateException.class,
                        () -> transaction(context).execute(status -> {
                            assertTrue(context.getBean(DeliveryClaimService.class)
                                    .claim(fixture.taskId()).isPresent());
                            written.countDown();
                            try {
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            throw new IllegalStateException("测试外层事务回滚");
                        })));
                try {
                    assertTrue(written.await(5, TimeUnit.SECONDS));
                    assertEquals(0L, countAttempts(jdbc, fixture.taskId()));
                    assertEquals("PENDING", taskStatus(jdbc, fixture.taskId()));
                    assertEquals("PENDING", eventStatus(jdbc, fixture.eventPk()));
                    release.countDown();
                    holder.get(5, TimeUnit.SECONDS);
                } finally {
                    release.countDown();
                }
            }
            assertEquals(0L, countAttempts(jdbc, fixture.taskId()));
            assertEquals("PENDING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, fixture.eventPk()));
            assertTrue(context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).isPresent());
        }
    }

    @Test
    void uniqueAttemptConflictRollsBackTaskAndEvent() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Fixture fixture = fixture(context, jdbc, true, DUE);
            jdbc.update("INSERT INTO delivery_attempt (task_id, attempt_no, result, started_at, request_id) "
                    + "VALUES (?, 1, 'STARTED', ?, ?)", fixture.taskId(), DUE, UUID.randomUUID().toString());

            assertThrows(DuplicateKeyException.class,
                    () -> context.getBean(DeliveryClaimService.class).claim(fixture.taskId()));
            assertEquals("PENDING", taskStatus(jdbc, fixture.taskId()));
            assertEquals("PENDING", eventStatus(jdbc, fixture.eventPk()));
            assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?",
                    Integer.class, fixture.taskId()));
            assertEquals(1L, countAttempts(jdbc, fixture.taskId()));
            assertEquals(null, jdbc.queryForObject("SELECT claim_token FROM delivery_task WHERE id = ?",
                    String.class, fixture.taskId()));
        }
    }

    private static Fixture fixture(ConfigurableApplicationContext context, JdbcTemplate jdbc,
            boolean enabled, LocalDateTime dueAt) {
        long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'delivery-claim-test-admin'",
                Long.class);
        long endpointId = context.getBean(EndpointSecretProvisioningService.class)
                .createSimulator("delivery-claim-" + UUID.randomUUID(), 5000, enabled, actor,
                        UUID.randomUUID().toString()).id();
        String eventId = "delivery-claim-" + UUID.randomUUID();
        jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                        + "endpoint_id, latest_round_no, current_status, created_at) "
                        + "VALUES (?, 'order.paid', 'null', UNHEX(SHA2('null', 256)), ?, 1, 'PENDING', ?)",
                eventId, endpointId, DUE);
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
        jdbc.update("INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                        + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                        + "target_type_snapshot, timeout_ms_snapshot, secret_ciphertext_snapshot, "
                        + "created_at, updated_at) "
                        + "SELECT ?, 1, 'PENDING', 0, 3, 10, 30, ?, 'SIMULATOR', 5000, "
                        + "signing_secret_ciphertext, ?, ? FROM endpoint WHERE id = ?",
                eventPk, dueAt, DUE, DUE, endpointId);
        long taskId = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?",
                Long.class, eventPk);
        return new Fixture(endpointId, eventPk, taskId);
    }

    private static long countAttempts(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                Long.class, taskId);
    }

    private static String taskStatus(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, taskId);
    }

    private static String eventStatus(JdbcTemplate jdbc, long eventPk) {
        return jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?", String.class, eventPk);
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static ConfigurableApplicationContext start() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=delivery-claim-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=delivery-claim-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }

    private record Fixture(long endpointId, long eventPk, long taskId) {
    }
}
