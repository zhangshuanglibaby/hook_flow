package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DueDeliveryScannerIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime DUE = LocalDateTime.of(2020, 1, 1, 0, 0);

    @AfterEach
    void clearFixtures() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM endpoint");
    }

    @Test
    void normalProfileSchedulesClaimAndAuthenticatesFormalSimulatorOverHttp() throws Exception {
        try (ConfigurableApplicationContext context = startNormal()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'due-scanner-test-admin'",
                    Long.class);
            long endpointId = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("scheduled-claim-" + UUID.randomUUID(), 5000, true, actor,
                            UUID.randomUUID().toString()).id();
            long taskId = insertTask(jdbc, endpointId, DUE);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (countAttempts(jdbc) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1L, countAttempts(jdbc));
            while ("DELIVERING".equals(taskStatus(jdbc, taskId)) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals("SUCCESS", taskStatus(jdbc, taskId));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ? AND result = 'SUCCESS' "
                            + "AND http_status = 200", Long.class, taskId));
            assertEquals("SUCCESS", jdbc.queryForObject(
                    "SELECT current_status FROM event WHERE id = (SELECT event_pk FROM delivery_task WHERE id = ?)",
                    String.class, taskId));
        }
    }

    @Test
    void scansSmallBatchesAndClaimsOnlyEligibleTasks() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DueDeliveryScanner scanner = context.getBean(DueDeliveryScanner.class);
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'due-scanner-test-admin'",
                    Long.class);
            long endpointId = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("due-scanner-enabled-" + UUID.randomUUID(), 5000, true, actor,
                            UUID.randomUUID().toString()).id();
            for (int i = 0; i < 18; i++) {
                insertTask(jdbc, endpointId, DUE);
            }
            long future = insertTask(jdbc, endpointId, DUE.plusYears(20));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (countAttempts(jdbc) < 18 && System.nanoTime() < deadline) {
                scanner.scanDueTasks();
                Thread.sleep(50);
            }
            long finishedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((countTasks(jdbc, "DELIVERING") > 0
                    || countAttemptsWithResult(jdbc, "STARTED") > 0) && System.nanoTime() < finishedDeadline) {
                Thread.sleep(50);
            }
            assertEquals(0L, countTasks(jdbc, "DELIVERING"));
            assertEquals(0L, countAttemptsWithResult(jdbc, "STARTED"));
            assertEquals(18L, countAttempts(jdbc));
            assertEquals("PENDING", taskStatus(jdbc, future));
        }
    }

    @Test
    void disabledDueTasksDoNotStarveLaterEnabledTasks() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'due-scanner-test-admin'",
                    Long.class);
            EndpointSecretProvisioningService provisioning = context.getBean(EndpointSecretProvisioningService.class);
            long disabled = provisioning.createSimulator("due-scanner-disabled-" + UUID.randomUUID(),
                    5000, false, actor, UUID.randomUUID().toString()).id();
            for (int i = 0; i < 16; i++) {
                insertTask(jdbc, disabled, DUE.minusDays(1));
            }
            long enabled = provisioning.createSimulator("due-scanner-active-" + UUID.randomUUID(),
                    5000, true, actor, UUID.randomUUID().toString()).id();
            long eligible = insertTask(jdbc, enabled, DUE);

            context.getBean(DueDeliveryScanner.class).scanDueTasks();
            assertEquals("PENDING", taskStatus(jdbc, eligible));
            assertEquals(0L, countAttempts(jdbc));
            context.getBean(DueDeliveryScanner.class).scanDueTasks();
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, eligible));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt a "
                    + "JOIN delivery_task t ON a.task_id = t.id JOIN event e ON t.event_pk = e.id "
                    + "WHERE e.endpoint_id = ?", Long.class, disabled));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (countAttemptsWithResult(jdbc, "STARTED") > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0L, countAttemptsWithResult(jdbc, "STARTED"));
        }
    }

    private static long insertTask(JdbcTemplate jdbc, long endpointId, LocalDateTime nextRunAt) {
        String eventId = "scanner-event-" + UUID.randomUUID();
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
                eventPk, nextRunAt, DUE, DUE, endpointId);
        return jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, eventPk);
    }

    private static long countTasks(JdbcTemplate jdbc, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE status = ?", Long.class, status);
    }

    private static long countAttempts(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class);
    }

    private static long countAttemptsWithResult(JdbcTemplate jdbc, String result) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE result = ?", Long.class, result);
    }

    private static String taskStatus(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, taskId);
    }

    private static ConfigurableApplicationContext startNormal() {
        return startWithProfile(null);
    }

    private static ConfigurableApplicationContext start() {
        return startWithProfile("p2-login-test");
    }

    private static ConfigurableApplicationContext startWithProfile(String profile) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var app = new SpringApplicationBuilder(HookflowBackendApplication.class);
        if (profile != null) {
            app.profiles(profile);
        }
        return app.run(
                "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=due-scanner-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=due-scanner-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
}
