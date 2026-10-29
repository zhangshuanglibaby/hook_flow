package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DeliveryCompletionServiceIntegrationTests {
    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime DUE = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Test
    void successAndRetryBranchesWriteAttemptTaskAndEventTogether() {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var claims = context.getBean(DeliveryClaimService.class);
            var completions = context.getBean(DeliveryCompletionService.class);
            var fixture = fixture(context, jdbc, 3);
            var first = claims.claim(fixture.taskId()).orElseThrow();
            assertTrue(completions.complete(first.taskId(), first.attemptNo(), first.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(503, "retry")));
            assertEquals("WAIT_RETRY", status(jdbc, fixture.taskId()));
            assertEquals("WAIT_RETRY", eventStatus(jdbc, fixture.eventPk()));
            LocalDateTime finished = attemptEnd(jdbc, fixture.taskId(), 1);
            assertEquals(finished.plusSeconds(10), nextRun(jdbc, fixture.taskId()));
            assertNull(jdbc.queryForObject("SELECT terminal_at FROM event WHERE id = ?",
                    (rs, row) -> rs.getObject(1, LocalDateTime.class), fixture.eventPk()));
            jdbc.update("UPDATE delivery_task SET next_run_at = ? WHERE id = ?", DUE, fixture.taskId());
            var second = claims.claim(fixture.taskId()).orElseThrow();
            assertTrue(completions.complete(second.taskId(), second.attemptNo(), second.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(429, "retry")));
            LocalDateTime secondEnd = attemptEnd(jdbc, fixture.taskId(), 2);
            assertEquals(secondEnd.plusSeconds(30), nextRun(jdbc, fixture.taskId()));
            jdbc.update("UPDATE delivery_task SET next_run_at = ? WHERE id = ?", DUE, fixture.taskId());
            var third = claims.claim(fixture.taskId()).orElseThrow();
            assertTrue(completions.complete(third.taskId(), third.attemptNo(), third.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("SUCCESS", status(jdbc, fixture.taskId()));
            assertEquals("SUCCESS", eventStatus(jdbc, fixture.eventPk()));
            assertEquals(attemptEnd(jdbc, fixture.taskId(), 3), jdbc.queryForObject(
                    "SELECT terminal_at FROM event WHERE id = ?",
                    (rs, row) -> rs.getObject(1, LocalDateTime.class), fixture.eventPk()));
            assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, fixture.taskId()));
            assertEquals("HTTP_FAILURE", attemptResult(jdbc, fixture.taskId(), 1));
            assertEquals("HTTP_FAILURE", attemptResult(jdbc, fixture.taskId(), 2));
            assertEquals("SUCCESS", attemptResult(jdbc, fixture.taskId(), 3));
            assertFalse(completions.complete(third.taskId(), third.attemptNo(), third.claimToken(),
                    SingleDeliveryHttpResult.unknown()));
        }
    }

    @Test
    void terminalFailuresUnknownAndPauseUseStoredPolicyAndCurrentEndpoint() {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var claims = context.getBean(DeliveryClaimService.class);
            var completions = context.getBean(DeliveryCompletionService.class);
            for (SingleDeliveryHttpResult failure : new SingleDeliveryHttpResult[] {
                    SingleDeliveryHttpResult.fromHttpResponse(302, "redirect"),
                    SingleDeliveryHttpResult.fromHttpResponse(404, "missing"),
                    SingleDeliveryHttpResult.localFailure(),
                    SingleDeliveryHttpResult.fromHttpResponse(500, "exhausted")}) {
                var fixture = fixture(context, jdbc, 1);
                var claim = claims.claim(fixture.taskId()).orElseThrow();
                assertTrue(completions.complete(claim.taskId(), claim.attemptNo(), claim.claimToken(), failure));
                assertEquals("FINAL_FAILED", status(jdbc, fixture.taskId()));
                assertEquals("FINAL_FAILED", eventStatus(jdbc, fixture.eventPk()));
                assertNull(nextRun(jdbc, fixture.taskId()));
                assertEquals(attemptEnd(jdbc, fixture.taskId(), 1), jdbc.queryForObject(
                        "SELECT finished_at FROM delivery_task WHERE id = ?",
                        (rs, row) -> rs.getObject(1, LocalDateTime.class), fixture.taskId()));
            }
            var unknown = fixture(context, jdbc, 3);
            var claim = claims.claim(unknown.taskId()).orElseThrow();
            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", unknown.endpointId());
            assertTrue(completions.complete(claim.taskId(), claim.attemptNo(), claim.claimToken(),
                    SingleDeliveryHttpResult.unknown()));
            assertEquals("PAUSED", status(jdbc, unknown.taskId()));
            assertEquals("PAUSED", eventStatus(jdbc, unknown.eventPk()));
            assertEquals("WAIT_RETRY", jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id = ?",
                    String.class, unknown.taskId()));
            assertEquals(attemptEnd(jdbc, unknown.taskId(), 1).plusSeconds(10), jdbc.queryForObject(
                    "SELECT resume_due_at FROM delivery_task WHERE id = ?",
                    (rs, row) -> rs.getObject(1, LocalDateTime.class), unknown.taskId()));
            assertNull(nextRun(jdbc, unknown.taskId()));
            assertEquals("UNKNOWN", attemptResult(jdbc, unknown.taskId(), 1));
        }
    }

    @Test
    void retryableResponsesAndUnknownUseSavedIntervalsAndExhaustion() {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var claims = context.getBean(DeliveryClaimService.class);
            var completions = context.getBean(DeliveryCompletionService.class);
            for (SingleDeliveryHttpResult outcome : new SingleDeliveryHttpResult[] {
                    SingleDeliveryHttpResult.fromHttpResponse(408, "timeout"),
                    SingleDeliveryHttpResult.fromHttpResponse(429, "rate limited"),
                    SingleDeliveryHttpResult.fromHttpResponse(500, "error"),
                    SingleDeliveryHttpResult.unknown()}) {
                var fixture = fixture(context, jdbc, 2);
                jdbc.update("UPDATE delivery_task SET retry_delay_1_s = 7.250, "
                        + "retry_delay_2_s = 19.500 WHERE id = ?", fixture.taskId());
                var first = claims.claim(fixture.taskId()).orElseThrow();
                assertTrue(completions.complete(first.taskId(), 1, first.claimToken(), outcome));
                assertEquals("WAIT_RETRY", status(jdbc, fixture.taskId()));
                assertEquals(attemptEnd(jdbc, fixture.taskId(), 1).plusNanos(7_250_000_000L),
                        nextRun(jdbc, fixture.taskId()));
                assertEquals(outcome.classification().result().name(), attemptResult(jdbc, fixture.taskId(), 1));
                jdbc.update("UPDATE delivery_task SET next_run_at = ? WHERE id = ?", DUE, fixture.taskId());
                var second = claims.claim(fixture.taskId()).orElseThrow();
                assertTrue(completions.complete(second.taskId(), 2, second.claimToken(), outcome));
                assertEquals("FINAL_FAILED", status(jdbc, fixture.taskId()));
                assertEquals("FINAL_FAILED", eventStatus(jdbc, fixture.eventPk()));
                assertEquals(outcome.classification().result().name(), attemptResult(jdbc, fixture.taskId(), 2));
                assertNull(nextRun(jdbc, fixture.taskId()));
            }
        }
    }

    @Test
    void snapshotFractionalDelayNeverSchedulesBeforeConfiguredInterval() {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var fixture = fixture(context, jdbc, 3);
            jdbc.update("UPDATE delivery_task SET retry_delay_1_s = 0.000000001 WHERE id = ?",
                    fixture.taskId());
            var claim = context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).orElseThrow();
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(
                    claim.taskId(), 1, claim.claimToken(), SingleDeliveryHttpResult.unknown()));
            assertEquals(attemptEnd(jdbc, fixture.taskId(), 1).plusNanos(1_000_000),
                    nextRun(jdbc, fixture.taskId()));
        }
    }

    @Test
    void oldTokenExpiredLeaseOldRoundAndEndedAttemptCannotOverwrite() {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var claims = context.getBean(DeliveryClaimService.class);
            var completions = context.getBean(DeliveryCompletionService.class);
            var wrongToken = fixture(context, jdbc, 3);
            var claim = claims.claim(wrongToken.taskId()).orElseThrow();
            assertFalse(completions.complete(claim.taskId(), 1, UUID.randomUUID().toString(),
                    SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("STARTED", attemptResult(jdbc, wrongToken.taskId(), 1));
            assertEquals("DELIVERING", eventStatus(jdbc, wrongToken.eventPk()));
            var expired = fixture(context, jdbc, 3);
            var expiredClaim = claims.claim(expired.taskId()).orElseThrow();
            jdbc.update("UPDATE delivery_task SET lease_until = ? WHERE id = ?", DUE, expired.taskId());
            assertFalse(completions.complete(expiredClaim.taskId(), 1, expiredClaim.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("STARTED", attemptResult(jdbc, expired.taskId(), 1));
            var oldRound = fixture(context, jdbc, 3);
            var oldClaim = claims.claim(oldRound.taskId()).orElseThrow();
            jdbc.update("UPDATE event SET latest_round_no = 2 WHERE id = ?", oldRound.eventPk());
            assertFalse(completions.complete(oldClaim.taskId(), 1, oldClaim.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("STARTED", attemptResult(jdbc, oldRound.taskId(), 1));
            var ended = fixture(context, jdbc, 3);
            var endedClaim = claims.claim(ended.taskId()).orElseThrow();
            jdbc.update("UPDATE delivery_attempt SET result = 'UNKNOWN', ended_at = started_at, "
                    + "duration_ms = 0 WHERE task_id = ?", ended.taskId());
            assertFalse(completions.complete(endedClaim.taskId(), 1, endedClaim.claimToken(),
                    SingleDeliveryHttpResult.fromHttpResponse(200, "ok")));
            assertEquals("DELIVERING", status(jdbc, ended.taskId()));
        }
    }

    @Test
    void concurrentCompletionsMutuallyExcludeAndNeverSplitRows() throws Exception {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var fixture = fixture(context, jdbc, 3);
            var claim = context.getBean(DeliveryClaimService.class).claim(fixture.taskId()).orElseThrow();
            var completion = context.getBean(DeliveryCompletionService.class);
            CountDownLatch start = new CountDownLatch(1);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = threads.submit(() -> { start.await(5, TimeUnit.SECONDS);
                    return completion.complete(claim.taskId(), 1, claim.claimToken(),
                            SingleDeliveryHttpResult.fromHttpResponse(200, "ok")); });
                var second = threads.submit(() -> { start.await(5, TimeUnit.SECONDS);
                    return completion.complete(claim.taskId(), 1, claim.claimToken(),
                            SingleDeliveryHttpResult.fromHttpResponse(500, "retry")); });
                start.countDown();
                assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            }
            String status = status(jdbc, fixture.taskId());
            assertTrue("SUCCESS".equals(status) || "WAIT_RETRY".equals(status));
            assertEquals(status, eventStatus(jdbc, fixture.eventPk()));
            assertEquals("SUCCESS".equals(status) ? "SUCCESS" : "HTTP_FAILURE",
                    attemptResult(jdbc, fixture.taskId(), 1));
        }
    }

    private static Fixture fixture(ConfigurableApplicationContext context, JdbcTemplate jdbc, int maxAttempts) {
        long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'completion-test-admin'", Long.class);
        long endpointId = context.getBean(EndpointSecretProvisioningService.class)
                .createSimulator("completion-" + UUID.randomUUID(), 5000, true, actor,
                        UUID.randomUUID().toString()).id();
        String eventId = "completion-" + UUID.randomUUID();
        jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                + "endpoint_id, latest_round_no, current_status, created_at) VALUES "
                + "(?, 'order.paid', 'null', UNHEX(SHA2('null',256)), ?, 1, 'PENDING', ?)",
                eventId, endpointId, DUE);
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
        jdbc.update("INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                + "target_type_snapshot, timeout_ms_snapshot, secret_ciphertext_snapshot, "
                + "created_at, updated_at) SELECT ?, 1, 'PENDING', 0, ?, 10, 30, ?, "
                + "'SIMULATOR', 5000, signing_secret_ciphertext, ?, ? FROM endpoint WHERE id = ?",
                eventPk, maxAttempts, DUE, DUE, DUE, endpointId);
        return new Fixture(endpointId, eventPk, jdbc.queryForObject(
                "SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, eventPk));
    }

    private static String status(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, id);
    }
    private static String eventStatus(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?", String.class, id);
    }
    private static String attemptResult(JdbcTemplate jdbc, long id, int no) {
        return jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id = ? AND attempt_no = ?",
                String.class, id, no);
    }
    private static LocalDateTime attemptEnd(JdbcTemplate jdbc, long id, int no) {
        return jdbc.queryForObject("SELECT ended_at FROM delivery_attempt WHERE task_id = ? AND attempt_no = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), id, no);
    }
    private static LocalDateTime nextRun(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), id);
    }
    private static ConfigurableApplicationContext start() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(), "--HOOKFLOW_ADMIN_USERNAME=completion-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=completion-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
    private record Fixture(long endpointId, long eventPk, long taskId) { }
}
