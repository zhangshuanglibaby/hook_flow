package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.endpoint.application.*;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.support.MutableClock;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import redis.clients.jedis.Jedis;
import tools.jackson.databind.node.JsonNodeFactory;

@Testcontainers
class DeliveryRedeliveryDeliveryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis@sha256:aa049e689e141a4358ad1d4562dc49c88a89fbab711fd8fcc33f684c80b26301").withExposedPorts(6379);
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-29T15:00:00.123Z"));
    static int receiverPort;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfiguration {
        @Bean @Primary MutableClock redeliveryDeliveryClock() { return CLOCK; }
        @Bean @Primary SimulatorInternalTargetRoute fixtureRoute() {
            return new SimulatorInternalTargetRoute(new MockEnvironment().withProperty("server.port", Integer.toString(receiverPort)));
        }
    }
    @AfterEach void clear() {
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("DELETE FROM delivery_attempt"); jdbc.update("DELETE FROM delivery_task"); jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM audit_log"); jdbc.update("DELETE FROM endpoint");
    }

    @Test void freshRoundUsesLatestTimeoutAndCiphertextAfterDisableEditEnableAndRealHttpHistoryRemainsReadOnly() throws Exception {
        try (var receiver = new Receiver(); var context = start(false, false)) {
            var jdbc = context.getBean(JdbcTemplate.class); Fixture f = create(context);
            scan(context); await(() -> "FINAL_FAILED".equals(state(jdbc, f.oldTask)));
            assertEquals(1, receiver.requests.get()); var old = task(jdbc, f.oldTask); var history = attempts(jdbc, f.oldTask);
            assertEquals(404, history.getFirst().get("http_status"));
            context.getBean(EndpointDisableService.class).disable(f.endpoint, actor(jdbc), id()).orElseThrow();
            assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(context, f));
            context.getBean(EndpointUpdateService.class).update(f.endpoint, null, 5000, actor(jdbc), id()).orElseThrow();
            context.getBean(EndpointEnableService.class).enable(f.endpoint, actor(jdbc), id()).orElseThrow();
            receiver.delayMillis = 700; receiver.status = 200;
            var fresh = redeliver(context, f); assertEquals(2, fresh.roundNo()); assertEquals(0, fresh.attemptCount());
            assertEquals(5000, task(jdbc, fresh.taskId()).get("timeout_ms_snapshot")); assertEquals(500, old.get("timeout_ms_snapshot"));
            assertArrayEquals(jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs, n) -> rs.getBytes(1), f.endpoint),
                    (byte[]) task(jdbc, fresh.taskId()).get("secret_ciphertext_snapshot"));
            assertEquals(1, receiver.requests.get()); assertTrue(attempts(jdbc, fresh.taskId()).isEmpty());
            concurrentScans(context); await(() -> "SUCCESS".equals(state(jdbc, fresh.taskId()))); concurrentScans(context);
            success(jdbc, f, fresh.taskId()); assertEquals(2, receiver.requests.get());
            DeliveryRedeliveryIntegrationTests.same(old, task(jdbc, f.oldTask)); assertEquals(history, attempts(jdbc, f.oldTask));
            assertEquals(List.of(f.eventId, f.eventId), new ArrayList<>(receiver.eventIds));
            assertEquals(List.of("{\"value\":1}", "{\"value\":1}"), new ArrayList<>(receiver.bodies));
        }
    }

    @Test void uncommittedNewRoundIsInvisibleToFormalScannerThenRepeatedScansAndRedisNotificationsClaimExactlyOnce() throws Exception {
        try (var receiver = new Receiver(); var context = start(false, true); var redis = redis()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            await(() -> redis.pubsubNumSub("hookflow:delivery:wakeup").get("hookflow:delivery:wakeup") >= 1);
            Fixture f = create(context); scan(context); await(() -> "FINAL_FAILED".equals(state(jdbc, f.oldTask)));
            var old = task(jdbc, f.oldTask); var history = attempts(jdbc, f.oldTask); receiver.status = 200;
            context.getBean(EndpointUpdateService.class).update(f.endpoint, null, 15000, actor(jdbc), id()).orElseThrow();
            List<BoundedDeliveryExecutor.Reservation> held = new ArrayList<>();
            CountDownLatch created = new CountDownLatch(1), release = new CountDownLatch(1);
            var workers = Executors.newSingleThreadExecutor();
            long newTask;
            try {
                Future<Long> writing = workers.submit(() -> transaction(context).execute(tx -> {
                    long task = redeliver(context, f).taskId(); created.countDown();
                    try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return task;
                }));
                assertTrue(created.await(8, TimeUnit.SECONDS));
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk=?", Long.class, f.eventPk));
                assertEquals("FINAL_FAILED", jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, f.eventPk));
                for (int i = 0; i < 20; i++) redis.publish("hookflow:delivery:wakeup", "scan");
                concurrentScans(context); assertEquals(1, receiver.requests.get()); assertEquals(1, attempts(jdbc, f.oldTask).size());
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED'", Long.class));
                for (int i = 0; i < 20; i++) held.add(context.getBean(BoundedDeliveryExecutor.class).reserve().orElseThrow());
                release.countDown(); newTask = writing.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
            try {
                for (int i = 0; i < 20; i++) redis.publish("hookflow:delivery:wakeup", "scan");
                concurrentScans(context); assertTrue(attempts(jdbc, newTask).isEmpty()); assertEquals("PENDING", state(jdbc, newTask));
                held.forEach(BoundedDeliveryExecutor.Reservation::close); held.clear();
                receiver.blockResponse = true;
                for (int i = 0; i < 40; i++) redis.publish("hookflow:delivery:wakeup", "scan");
                assertTrue(receiver.newRequest.await(5, TimeUnit.SECONDS));
                concurrentScans(context);
                assertEquals(1, attempts(jdbc, newTask).size()); assertEquals("STARTED", attempts(jdbc, newTask).getFirst().get("result"));
                assertEquals(1, task(jdbc, newTask).get("attempt_count")); assertEquals(2, receiver.requests.get());
                receiver.release.countDown(); await(() -> "SUCCESS".equals(state(jdbc, newTask)));
                for (int i = 0; i < 20; i++) redis.publish("hookflow:delivery:wakeup", "scan"); concurrentScans(context);
                success(jdbc, f, newTask); assertEquals(2, receiver.requests.get());
                DeliveryRedeliveryIntegrationTests.same(old, task(jdbc, f.oldTask)); assertEquals(history, attempts(jdbc, f.oldTask));
            } finally { held.forEach(BoundedDeliveryExecutor.Reservation::close); }
        }
    }

    @Test void periodicMysqlScanExecutesRedeliveryWithoutAnyRedisHintOrManualScan() throws Exception {
        try (var receiver = new Receiver(); var context = start(true, false)) {
            var jdbc = context.getBean(JdbcTemplate.class); Fixture f = create(context);
            await(() -> "FINAL_FAILED".equals(state(jdbc, f.oldTask)));
            var old = task(jdbc, f.oldTask); var history = attempts(jdbc, f.oldTask);
            List<BoundedDeliveryExecutor.Reservation> held = new ArrayList<>();
            try {
                for (int i = 0; i < 20; i++) held.add(context.getBean(BoundedDeliveryExecutor.class).reserve().orElseThrow());
                receiver.status = 200; var fresh = redeliver(context, f);
                assertEquals("PENDING", state(jdbc, fresh.taskId())); assertTrue(attempts(jdbc, fresh.taskId()).isEmpty()); assertEquals(1, receiver.requests.get());
                held.forEach(BoundedDeliveryExecutor.Reservation::close); held.clear();
                await(() -> "SUCCESS".equals(state(jdbc, fresh.taskId())));
                success(jdbc, f, fresh.taskId()); assertEquals(2, receiver.requests.get());
                DeliveryRedeliveryIntegrationTests.same(old, task(jdbc, f.oldTask)); assertEquals(history, attempts(jdbc, f.oldTask));
            } finally { held.forEach(BoundedDeliveryExecutor.Reservation::close); }
        }
    }

    static Fixture create(ConfigurableApplicationContext context) {
        var jdbc = context.getBean(JdbcTemplate.class);
        long ep = context.getBean(EndpointSecretProvisioningService.class).createSimulator("redelivery-controlled-receiver", 500, true, actor(jdbc), id()).id();
        String external = "real-redelivery-" + id();
        context.getBean(EventCreationService.class).create(external, "order.paid", ep,
                new EventPayloadCanonicalizer().canonicalize(JsonNodeFactory.instance.objectNode().put("value", 1))).orElseThrow();
        var row = jdbc.queryForMap("SELECT e.id AS event_pk,t.id AS task FROM event e JOIN delivery_task t ON t.event_pk=e.id WHERE e.event_id=?", external);
        return new Fixture(ep, ((Number) row.get("event_pk")).longValue(), ((Number) row.get("task")).longValue(), external);
    }
    static DeliveryRedeliveryService.TaskSummary redeliver(ConfigurableApplicationContext context, Fixture f) { return context.getBean(DeliveryRedeliveryService.class).redeliver(f.eventId, actor(context.getBean(JdbcTemplate.class)), id()); }
    static long actor(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT id FROM app_user WHERE username='redelivery-delivery'", Long.class); }
    static String id() { return UUID.randomUUID().toString(); }
    static String state(JdbcTemplate jdbc, long task) { return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?", String.class, task); }
    static Map<String,Object> task(JdbcTemplate jdbc, long task) { return jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task); }
    static List<Map<String,Object>> attempts(JdbcTemplate jdbc, long task) { return jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", task); }
    static void success(JdbcTemplate jdbc, Fixture f, long task) {
        assertEquals("SUCCESS", state(jdbc, task)); assertEquals("SUCCESS", jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, f.eventPk));
        assertEquals(2, jdbc.queryForObject("SELECT latest_round_no FROM event WHERE id=?", Integer.class, f.eventPk));
        assertEquals(1, task(jdbc, task).get("attempt_count")); var attempts = attempts(jdbc, task); assertEquals(1, attempts.size());
        assertEquals(1, attempts.getFirst().get("attempt_no")); assertEquals("SUCCESS", attempts.getFirst().get("result")); assertEquals(200, attempts.getFirst().get("http_status"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk=?", Long.class, f.eventPk));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED' AND object_id=?", Long.class, Long.toString(f.eventPk)));
    }
    static void scan(ConfigurableApplicationContext context) { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
    static void concurrentScans(ConfigurableApplicationContext context) throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>(); for (int i = 0; i < 20; i++) futures.add(workers.submit(() -> scan(context)));
            for (var future : futures) future.get(8, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(15);
        assertTrue(condition.getAsBoolean());
    }
    static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(12); return tx;
    }
    static Jedis redis() { return new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379), 1000); }
    static ConfigurableApplicationContext start(boolean scheduled, boolean redis) {
        CLOCK.setInstant(Instant.parse("2026-10-29T15:00:00.123Z"));
        byte[] encryption = new byte[32]; new java.security.SecureRandom().nextBytes(encryption);
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(), "--HOOKFLOW_ADMIN_USERNAME=redelivery-delivery", "--HOOKFLOW_ADMIN_PASSWORD=redelivery-delivery-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(encryption), "--HOOKFLOW_REDIS_ENABLED=" + redis,
                "--REDIS_HOST=" + REDIS.getHost(), "--REDIS_PORT=" + REDIS.getMappedPort(6379),
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5"));
        if (!scheduled) args.add("--spring.profiles.active=p2-login-test");
        return new SpringApplicationBuilder(HookflowBackendApplication.class, ReceiverConfiguration.class).run(args.toArray(String[]::new));
    }
    record Fixture(long endpoint, long eventPk, long oldTask, String eventId) { }

    static class Receiver implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final Queue<String> eventIds = new ConcurrentLinkedQueue<>(), bodies = new ConcurrentLinkedQueue<>();
        final CountDownLatch newRequest = new CountDownLatch(1), release = new CountDownLatch(1);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final HttpServer server;
        volatile int status = 404;
        volatile int delayMillis;
        volatile boolean blockResponse;
        Receiver() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext(SimulatorInternalTargetRoute.PATH, exchange -> {
                try {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    eventIds.add(exchange.getRequestHeaders().getFirst("X-HookFlow-Event-Id")); requests.incrementAndGet();
                    if (blockResponse) { newRequest.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    byte[] body = "controlled-redelivery-receiver".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start(); receiverPort = server.getAddress().getPort();
        }
        @Override public void close() throws Exception { release.countDown(); server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(6, TimeUnit.SECONDS)); }
    }
}
