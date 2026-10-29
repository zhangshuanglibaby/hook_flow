package com.hookflow.event.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class EventClaimGuardIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 8, 30);

    @Test
    void locksAndChecksEndpointBeforeSynchronizingCurrentRoundStatus() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            EventClaimGuard guard = context.getBean(EventClaimGuard.class);
            long endpointId = endpoint(context, jdbc);
            long eventPk = event(jdbc, endpointId);
            TransactionTemplate transaction = transaction(context);

            assertEquals(endpointId, guard.findEndpointIdForClaim(eventPk).orElseThrow());
            assertTrue(guard.findEndpointIdForClaim(Long.MAX_VALUE).isEmpty());
            assertThrows(IllegalTransactionStateException.class,
                    () -> guard.lockForClaim(eventPk, endpointId));
            assertThrows(IllegalTransactionStateException.class, () ->
                    guard.markDelivering(new EventClaimGuard.ClaimEvent(eventPk, endpointId, 1, "PENDING")));
            assertEquals(Boolean.TRUE, transaction.execute(
                    status -> guard.lockForClaim(eventPk, endpointId + 1).isEmpty()));
            assertEquals(Boolean.TRUE, transaction.execute(
                    status -> guard.lockForClaim(Long.MAX_VALUE, endpointId).isEmpty()));

            assertEquals(Boolean.TRUE, transaction.execute(status -> {
                EventClaimGuard.ClaimEvent locked = guard.lockForClaim(eventPk, endpointId).orElseThrow();
                assertEquals(endpointId, locked.endpointId());
                assertEquals(1, locked.latestRoundNo());
                assertEquals("PENDING", locked.currentStatus());
                return guard.markDelivering(locked);
            }));
            assertEquals("DELIVERING", jdbc.queryForObject(
                    "SELECT current_status FROM event WHERE id = ?", String.class, eventPk));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT latest_round_no FROM event WHERE id = ?", Integer.class, eventPk));
            assertThrows(IllegalArgumentException.class, () -> transaction.execute(status ->
                    guard.markDelivering(guard.lockForClaim(eventPk, endpointId).orElseThrow())));
        }
    }

    @Test
    void eventStateUpdateRollsBackWithCallerTransaction() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            EventClaimGuard guard = context.getBean(EventClaimGuard.class);
            long endpointId = endpoint(context, jdbc);
            long eventPk = event(jdbc, endpointId);

            assertThrows(IllegalStateException.class, () -> transaction(context).execute(status -> {
                assertEquals(Boolean.TRUE,
                        guard.markDelivering(guard.lockForClaim(eventPk, endpointId).orElseThrow()));
                throw new IllegalStateException("测试事务回滚");
            }));
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT current_status FROM event WHERE id = ?", String.class, eventPk));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT latest_round_no FROM event WHERE id = ?", Integer.class, eventPk));
        }
    }

    private static long endpoint(ConfigurableApplicationContext context, JdbcTemplate jdbc) {
        long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'event-claim-test-admin'",
                Long.class);
        return context.getBean(EndpointSecretProvisioningService.class)
                .createSimulator("event-claim-" + UUID.randomUUID(), 5000, actor,
                        UUID.randomUUID().toString()).id();
    }

    private static long event(JdbcTemplate jdbc, long endpointId) {
        String eventId = "event-claim-" + UUID.randomUUID();
        jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                        + "endpoint_id, latest_round_no, current_status, created_at) "
                        + "VALUES (?, 'order.paid', 'null', UNHEX(SHA2('null', 256)), ?, 1, 'PENDING', ?)",
                eventId, endpointId, NOW);
        return jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
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
                "--HOOKFLOW_ADMIN_USERNAME=event-claim-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=event-claim-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
}
