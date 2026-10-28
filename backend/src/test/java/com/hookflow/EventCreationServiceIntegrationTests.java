package com.hookflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.endpoint.application.EndpointTaskSnapshotService.EndpointDisabledException;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.event.api.EventRequestJsonParser;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.CanonicalEventPayload;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class EventCreationServiceIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @Test
    void commitsCanonicalEventAndFirstTaskWithLockedEndpointSnapshot() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'event-creation-test-admin'", Long.class);
            EndpointSecretProvisioningService provisioning =
                    context.getBean(EndpointSecretProvisioningService.class);
            EndpointConfiguration endpoint = provisioning.createExternal(
                    "event-creation-external-" + UUID.randomUUID(),
                    "https://hooks.acme.com/receive", 6500, true, actor, UUID.randomUUID().toString())
                    .configuration();
            byte[] ciphertext = jdbc.queryForObject(
                    "SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?",
                    (rs, row) -> rs.getBytes(1), endpoint.id());
            EventCreationService service = context.getBean(EventCreationService.class);
            CanonicalEventPayload payload = payload("{\"z\":[1.0,2],\"a\":true}");
            String eventId = "create-success-" + UUID.randomUUID();

            EventCreationService.CreatedEvent created =
                    service.create(eventId, "order.paid", endpoint.id(), payload).orElseThrow();

            Long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?",
                    Long.class, eventId);
            assertEquals(eventId, created.eventId());
            assertEquals("order.paid", created.eventType());
            assertEquals(endpoint.id(), created.endpointId());
            assertEquals(LocalDateTime.ofInstant(created.createdAt(), ZoneOffset.UTC),
                    jdbc.queryForObject("SELECT created_at FROM event WHERE id = ?",
                            (rs, row) -> rs.getObject(1, LocalDateTime.class), eventPk));
            assertEquals("{\"a\":true,\"z\":[1,2]}", jdbc.queryForObject(
                    "SELECT payload_json FROM event WHERE id = ?", String.class, eventPk));
            assertArrayEquals(payload.fingerprint(), jdbc.queryForObject(
                    "SELECT payload_fingerprint FROM event WHERE id = ?",
                    (rs, row) -> rs.getBytes(1), eventPk));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE id = ? AND latest_round_no = 1 "
                            + "AND current_status = 'PENDING' AND terminal_at IS NULL",
                    Long.class, eventPk));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ? AND round_no = 1 "
                            + "AND status = 'PENDING' AND attempt_count = 0 "
                            + "AND max_attempts = 3 AND next_run_at = created_at "
                            + "AND updated_at = created_at AND finished_at IS NULL",
                    Long.class, eventPk));
            assertEquals("EXTERNAL", jdbc.queryForObject(
                    "SELECT target_type_snapshot FROM delivery_task WHERE event_pk = ?",
                    String.class, eventPk));
            assertEquals(endpoint.url(), jdbc.queryForObject(
                    "SELECT url_snapshot FROM delivery_task WHERE event_pk = ?",
                    String.class, eventPk));
            assertEquals(endpoint.timeoutMs(), jdbc.queryForObject(
                    "SELECT timeout_ms_snapshot FROM delivery_task WHERE event_pk = ?",
                    Integer.class, eventPk));
            assertArrayEquals(ciphertext, jdbc.queryForObject(
                    "SELECT secret_ciphertext_snapshot FROM delivery_task WHERE event_pk = ?",
                    (rs, row) -> rs.getBytes(1), eventPk));
            assertEquals(new BigDecimal("10.000000000"), jdbc.queryForObject(
                    "SELECT retry_delay_1_s FROM delivery_task WHERE event_pk = ?",
                    (rs, row) -> rs.getBigDecimal(1), eventPk));
            assertEquals(new BigDecimal("30.000000000"), jdbc.queryForObject(
                    "SELECT retry_delay_2_s FROM delivery_task WHERE event_pk = ?",
                    (rs, row) -> rs.getBigDecimal(1), eventPk));
        }
    }

    @Test
    void missingAndDisabledEndpointsLeaveNoEventOrTask() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'event-creation-test-admin'", Long.class);
            EndpointConfiguration disabled = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("event-creation-disabled-" + UUID.randomUUID(),
                            5000, false, actor, UUID.randomUUID().toString());
            EventCreationService service = context.getBean(EventCreationService.class);
            CanonicalEventPayload payload = payload("null");
            String missingId = "create-missing-" + UUID.randomUUID();
            String disabledId = "create-disabled-" + UUID.randomUUID();
            Long beforeTasks = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class);

            assertTrue(service.create(missingId, "order.paid", Long.MAX_VALUE, payload).isEmpty());
            assertThrows(EndpointDisabledException.class, () ->
                    service.create(disabledId, "order.paid", disabled.id(), payload));

            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE event_id IN (?, ?)",
                    Long.class, missingId, disabledId));
            assertEquals(beforeTasks, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class));
        }
    }

    @Test
    void taskInsertFailureRollsBackCreatedEvent() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'event-creation-test-admin'", Long.class);
            EndpointConfiguration endpoint = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("event-creation-rollback-" + UUID.randomUUID(),
                            5000, actor, UUID.randomUUID().toString());
            String eventId = "create-rollback-" + UUID.randomUUID();
            Long nextEventPk = jdbc.queryForObject(
                    "SELECT AUTO_INCREMENT FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event'",
                    Long.class);
            jdbc.execute("ALTER TABLE delivery_task ADD CONSTRAINT ck_test_reject_event "
                    + "CHECK (event_pk <> " + nextEventPk + ")");
            try {
                assertThrows(DataAccessException.class, () -> context.getBean(EventCreationService.class)
                        .create(eventId, "order.paid", endpoint.id(), payload("null")));
            } finally {
                jdbc.execute("ALTER TABLE delivery_task DROP CHECK ck_test_reject_event");
            }

            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE event_id = ?", Long.class, eventId));
            assertFalse(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM delivery_task t JOIN event e ON e.id = t.event_pk "
                            + "WHERE e.event_id = ?)", Boolean.class, eventId));
        }
    }

    private static CanonicalEventPayload payload(String json) {
        String request = "{\"payload\":" + json + "}";
        return new EventRequestJsonParser().parse(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8))).payload();
    }

    private static ConfigurableApplicationContext start() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test",
                "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=event-creation-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=event-creation-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(keyBytes));
    }
}
