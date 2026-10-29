package com.hookflow.event.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hookflow.delivery.infrastructure.InitialDeliveryTaskRepository;
import com.hookflow.endpoint.domain.EndpointTargetType;
import com.hookflow.endpoint.domain.EndpointTaskSnapshot;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.event.api.EventRequestJsonParser;
import com.hookflow.event.domain.CanonicalEventPayload;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class InitialEventPersistenceIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 34, 56, 789_000_000);

    @Test
    void storesCanonicalEventAndInitialTaskWithIndependentSnapshots() {
        JdbcTemplate jdbc = jdbc();
        long endpointId = insertEndpoint(jdbc, EndpointTargetType.EXTERNAL);
        byte[] ciphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?",
                (rs, row) -> rs.getBytes(1), endpointId);
        EndpointTaskSnapshot snapshot = new EndpointTaskSnapshot(EndpointTargetType.EXTERNAL,
                "https://hooks.example.com/receive", 5000, ciphertext);
        CanonicalEventPayload payload = payload("{\"z\":[1.0,2],\"a\":true}");
        EventRepository events = new EventRepository(jdbc);
        InitialDeliveryTaskRepository tasks = new InitialDeliveryTaskRepository(jdbc);
        String eventId = "repository-success-" + UUID.randomUUID();

        long eventPk = transaction(jdbc).execute(status -> {
            long pk = events.insert(eventId, "order.paid", endpointId, payload, NOW);
            tasks.insert(pk, snapshot, 3, new BigDecimal("10.250000000"),
                    new BigDecimal("30.000000001"), NOW);
            return pk;
        });

        assertEquals("{\"a\":true,\"z\":[1,2]}", jdbc.queryForObject(
                "SELECT payload_json FROM event WHERE id = ?", String.class, eventPk));
        assertArrayEquals(payload.fingerprint(), jdbc.queryForObject(
                "SELECT payload_fingerprint FROM event WHERE id = ?", (rs, row) -> rs.getBytes(1), eventPk));
        assertEquals(1, jdbc.queryForObject("SELECT latest_round_no FROM event WHERE id = ?",
                Integer.class, eventPk));
        assertEquals("PENDING", jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?",
                String.class, eventPk));
        assertEquals(NOW, jdbc.queryForObject("SELECT created_at FROM event WHERE id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), eventPk));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ? AND round_no = 1 "
                        + "AND status = 'PENDING' AND attempt_count = 0 AND max_attempts = 3 "
                        + "AND next_run_at = created_at AND updated_at = created_at",
                Long.class, eventPk));
        assertEquals(new BigDecimal("10.250000000"), jdbc.queryForObject(
                "SELECT retry_delay_1_s FROM delivery_task WHERE event_pk = ?",
                (rs, row) -> rs.getBigDecimal(1), eventPk));
        assertEquals(new BigDecimal("30.000000001"), jdbc.queryForObject(
                "SELECT retry_delay_2_s FROM delivery_task WHERE event_pk = ?",
                (rs, row) -> rs.getBigDecimal(1), eventPk));
        assertEquals("EXTERNAL", jdbc.queryForObject(
                "SELECT target_type_snapshot FROM delivery_task WHERE event_pk = ?",
                String.class, eventPk));
        assertEquals(snapshot.url(), jdbc.queryForObject(
                "SELECT url_snapshot FROM delivery_task WHERE event_pk = ?", String.class, eventPk));
        assertEquals(snapshot.timeoutMs(), jdbc.queryForObject(
                "SELECT timeout_ms_snapshot FROM delivery_task WHERE event_pk = ?", Integer.class, eventPk));
        assertArrayEquals(ciphertext, jdbc.queryForObject(
                "SELECT secret_ciphertext_snapshot FROM delivery_task WHERE event_pk = ?",
                (rs, row) -> rs.getBytes(1), eventPk));
    }

    @Test
    void failedTaskInsertRollsBackEventInCallerTransaction() {
        JdbcTemplate jdbc = jdbc();
        long endpointId = insertEndpoint(jdbc, EndpointTargetType.SIMULATOR);
        byte[] ciphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?",
                (rs, row) -> rs.getBytes(1), endpointId);
        EndpointTaskSnapshot snapshot = new EndpointTaskSnapshot(EndpointTargetType.SIMULATOR,
                null, 5000, ciphertext);
        EventRepository events = new EventRepository(jdbc);
        InitialDeliveryTaskRepository tasks = new InitialDeliveryTaskRepository(jdbc);
        String eventId = "repository-rollback-" + UUID.randomUUID();

        assertThrows(DataAccessException.class, () -> transaction(jdbc).execute(status -> {
            long eventPk = events.insert(eventId, "order.paid", endpointId, payload("null"), NOW);
            tasks.insert(eventPk, snapshot, 0, BigDecimal.TEN, new BigDecimal("30"), NOW);
            return null;
        }));

        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE event_id = ?",
                Long.class, eventId));
    }

    @Test
    void lockedEventReadHoldsRowUntilCommitAndSeesCurrentState() throws Exception {
        JdbcTemplate jdbc = jdbc();
        EventRepository events = new EventRepository(jdbc);
        long endpointId = insertEndpoint(jdbc, EndpointTargetType.SIMULATOR);
        long eventPk = events.insert("locked-event-" + UUID.randomUUID(), "order.paid",
                endpointId, payload("null"), NOW);
        assertTrue(events.findEndpointId(Long.MAX_VALUE).isEmpty());
        assertTrue(Boolean.TRUE.equals(transaction(jdbc).execute(
                status -> events.findByIdForUpdate(Long.MAX_VALUE).isEmpty())));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = threads.submit(() -> transaction(jdbc).execute(status -> {
                EventRepository.LockedEvent event = events.findByIdForUpdate(eventPk).orElseThrow();
                assertEquals(eventPk, event.id());
                assertEquals(endpointId, event.endpointId());
                assertEquals(1, event.latestRoundNo());
                assertEquals("PENDING", event.currentStatus());
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
                var locate = threads.submit(() -> events.findEndpointId(eventPk).orElseThrow());
                assertEquals(endpointId, locate.get(5, TimeUnit.SECONDS));
                var updater = threads.submit(() -> jdbc.update(
                        "UPDATE event SET current_status = 'WAIT_RETRY' WHERE id = ?", eventPk));
                assertThrows(TimeoutException.class, () -> updater.get(250, TimeUnit.MILLISECONDS));
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
                assertEquals(1, updater.get(5, TimeUnit.SECONDS));
                assertEquals("WAIT_RETRY", transaction(jdbc).execute(status ->
                        events.findByIdForUpdate(eventPk).orElseThrow().currentStatus()));
            } finally {
                release.countDown();
            }
        }
    }

    private static JdbcTemplate jdbc() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource source = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        return new JdbcTemplate(source);
    }

    private static TransactionTemplate transaction(JdbcTemplate jdbc) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static long insertEndpoint(JdbcTemplate jdbc, EndpointTargetType type) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(
                Base64.getEncoder().encodeToString(key));
        byte[] ciphertext = cipher.encrypt(cipher.generateSecret());
        String name = "repository-" + UUID.randomUUID();
        String url = type == EndpointTargetType.EXTERNAL ? "https://hooks.example.com/receive" : null;
        jdbc.update("INSERT INTO endpoint (name, target_type, url, timeout_ms, enabled, "
                        + "signing_secret_ciphertext, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 5000, 1, ?, ?, ?)",
                name, type.name(), url, ciphertext, NOW, NOW);
        return jdbc.queryForObject("SELECT id FROM endpoint WHERE name = ?", Long.class, name);
    }

    private static CanonicalEventPayload payload(String json) {
        String request = "{\"payload\":" + json + "}";
        return new EventRequestJsonParser().parse(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8))).payload();
    }
}
