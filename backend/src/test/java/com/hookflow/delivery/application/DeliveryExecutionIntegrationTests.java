package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.support.TestHttpReceiver;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DeliveryExecutionIntegrationTests {
    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime DUE = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Test
    void scannerSendsRealHttpAfterClaimCommitAndSavesSignedResult() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'execution-test-admin'",
                    Long.class);
            long endpoint = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("execution-" + UUID.randomUUID(), 5000, true, actor,
                            UUID.randomUUID().toString()).id();
            String eventId = "execution-" + UUID.randomUUID();
            jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                    + "endpoint_id, latest_round_no, current_status, created_at) VALUES "
                    + "(?, 'order.paid', '{\"amount\":1}', UNHEX(SHA2('{\"amount\":1}',256)), ?, 1, 'PENDING', ?)",
                    eventId, endpoint, DUE);
            long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
            jdbc.update("INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                    + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                    + "target_type_snapshot, timeout_ms_snapshot, secret_ciphertext_snapshot, "
                    + "created_at, updated_at) SELECT ?, 1, 'PENDING', 0, 3, 10, 30, ?, "
                    + "'SIMULATOR', 5000, signing_secret_ciphertext, ?, ? FROM endpoint WHERE id = ?",
                    eventPk, DUE, DUE, DUE, endpoint);
            long taskId = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, eventPk);
            context.getBean(DueDeliveryScanner.class).scanDueTasks();
            var request = receiver.awaitRequest(Duration.ofSeconds(5));
            assertTrue(request != null);
            assertEquals("POST", request.method());
            assertEquals(SimulatorInternalTargetRoute.PATH, request.uri().getPath());
            assertEquals("{\"amount\":1}", request.body());
            assertEquals(eventId, request.headers().getFirst("X-HookFlow-Event-Id"));
            assertTrue(request.headers().getFirst("X-HookFlow-Signature") != null);
            String secret = context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(
                    jdbc.queryForObject("SELECT secret_ciphertext_snapshot FROM delivery_task WHERE id = ?",
                            (rs, row) -> rs.getBytes(1), taskId));
            String timestamp = request.headers().getFirst("X-HookFlow-Timestamp");
            assertEquals(context.getBean(DeliveryRequestSigner.class).sign(eventId, timestamp,
                            request.body().getBytes(StandardCharsets.UTF_8), secret),
                    request.headers().getFirst("X-HookFlow-Signature"));
            LocalDateTime started = jdbc.queryForObject(
                    "SELECT started_at FROM delivery_attempt WHERE task_id = ?",
                    (rs, row) -> rs.getObject(1, LocalDateTime.class), taskId);
            assertTrue(started != null);
            assertTrue(!LocalDateTime.ofInstant(java.time.Instant.parse(timestamp)
                    .truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC).isBefore(started));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt "
                    + "WHERE task_id = ? AND attempt_no = 1", Long.class, taskId));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCESS".equals(jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?",
                    String.class, taskId)) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?",
                    String.class, taskId));
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?",
                    String.class, eventPk));
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id = ?",
                    String.class, taskId));
            assertEquals(200, jdbc.queryForObject("SELECT http_status FROM delivery_attempt WHERE task_id = ?",
                    Integer.class, taskId));
            assertEquals("ok", jdbc.queryForObject("SELECT response_excerpt FROM delivery_attempt WHERE task_id = ?",
                    String.class, taskId));
            assertTrue(receiver.awaitRequest(Duration.ofMillis(250)) == null);
            assertFalse(context.getBean(DeliveryClaimService.class).claim(taskId).isPresent());
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ReceiverRoute {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        SimulatorInternalTargetRoute executionReceiverRoute(org.springframework.core.env.Environment environment) {
            return new SimulatorInternalTargetRoute(new org.springframework.mock.env.MockEnvironment()
                    .withProperty("local.server.port", environment.getRequiredProperty("p1001.receiver.port")));
        }
    }

    private static ConfigurableApplicationContext start(TestHttpReceiver receiver) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SpringApplicationBuilder(HookflowBackendApplication.class, ReceiverRoute.class).run(
                "--spring.profiles.active=p2-login-test",
                "--server.port=0", "--p1001.receiver.port=" + receiver.simulatorUri().getPort(),
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(), "--HOOKFLOW_ADMIN_USERNAME=execution-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=execution-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
}
