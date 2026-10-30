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
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

@Testcontainers
class EndpointEnableDeliveryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-29T14:00:00.123Z"));
    static int receiverPort;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfiguration {
        @Bean @Primary MutableClock enableDeliveryClock() { return CLOCK; }
        @Bean @Primary SimulatorInternalTargetRoute fixtureRoute() {
            return new SimulatorInternalTargetRoute(new MockEnvironment().withProperty("server.port", Integer.toString(receiverPort)));
        }
    }

    @Test void pendingResumesThroughRealHttpUsingOldTimeoutAndOnlyOneAttempt() throws Exception {
        try (var receiver = new Receiver(false); var context = start(false)) {
            var jdbc = context.getBean(JdbcTemplate.class); long ep = endpoint(context);
            long task = create(context, ep); var original = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task);
            disable(context, ep); assertEquals("PAUSED", state(jdbc, task));
            context.getBean(EndpointUpdateService.class).update(ep, null, 1, actor(jdbc), id()).orElseThrow();
            // 旧快照为 5000ms；夹具延迟超过编辑后的 1ms，检验实际 HTTP 没有使用新超时。
            receiver.delayMillis = 100;
            enable(context, ep); assertEquals("PENDING", state(jdbc, task)); assertEquals(0, attemptCount(jdbc, task));
            assertSnapshot(original, jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task));
            scanRepeatedly(context); await(() -> "SUCCESS".equals(state(jdbc, task)));
            consistent(jdbc, task, "SUCCESS"); assertEquals(1, receiver.requests.get()); assertEquals(1, attemptCount(jdbc, task));
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?", String.class, task));
            assertEquals(200, jdbc.queryForObject("SELECT http_status FROM delivery_attempt WHERE task_id=?", Integer.class, task));
            enable(context, ep); scanRepeatedly(context);
            assertEquals(1, receiver.requests.get()); assertEquals(1, attemptCount(jdbc, task));
            assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?", Integer.class, task));
            assertEquals(5000, jdbc.queryForObject("SELECT timeout_ms_snapshot FROM delivery_task WHERE id=?", Integer.class, task));
        }
    }

    @Test void retryResumesButCannotStartBeforeOriginalDueAndRepeatedScansDoNotDuplicate() throws Exception {
        try (var receiver = new Receiver(true); var context = start(false)) {
            var jdbc = context.getBean(JdbcTemplate.class); long ep = endpoint(context); long task = create(context, ep);
            context.getBean(DueDeliveryScanner.class).scanDueTasks(); await(() -> "WAIT_RETRY".equals(state(jdbc, task)));
            LocalDateTime due = time(jdbc, task, "next_run_at");
            LocalDateTime ended = jdbc.queryForObject("SELECT ended_at FROM delivery_attempt WHERE task_id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task);
            assertEquals(ended.plusSeconds(10), due); assertEquals(1, receiver.requests.get());
            var original = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task);
            disable(context, ep); assertEquals("PAUSED", state(jdbc, task)); assertEquals(due, time(jdbc, task, "resume_due_at"));
            CLOCK.advance(Duration.ofSeconds(2)); enable(context, ep);
            assertEquals("WAIT_RETRY", state(jdbc, task)); assertEquals(due, time(jdbc, task, "next_run_at"));
            assertNull(time(jdbc, task, "resume_due_at"));
            assertNull(jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id=?", String.class, task));
            assertSnapshot(original, jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task));
            CLOCK.setInstant(due.toInstant(ZoneOffset.UTC).minusMillis(1)); scanRepeatedly(context);
            consistent(jdbc, task, "WAIT_RETRY"); assertEquals(1, receiver.requests.get()); assertEquals(1, attemptCount(jdbc, task));
            CLOCK.setInstant(due.toInstant(ZoneOffset.UTC));
            ExecutorService scanners = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 20; i++) futures.add(scanners.submit(() -> context.getBean(DueDeliveryScanner.class).scanDueTasks()));
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            } finally { scanners.shutdownNow(); assertTrue(scanners.awaitTermination(5, TimeUnit.SECONDS)); }
            await(() -> "SUCCESS".equals(state(jdbc, task))); scanRepeatedly(context);
            consistent(jdbc, task, "SUCCESS"); assertEquals(2, receiver.requests.get()); assertEquals(2, attemptCount(jdbc, task));
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?", Integer.class, task));
            assertEquals(List.of("HTTP_FAILURE", "SUCCESS"), jdbc.queryForList("SELECT result FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", String.class, task));
            LocalDateTime started = jdbc.queryForObject("SELECT started_at FROM delivery_attempt WHERE task_id=? AND attempt_no=2", (rs, row) -> rs.getObject(1, LocalDateTime.class), task);
            assertEquals(due, started);
        }
    }

    @Test void restoredPendingRunsFromPeriodicMysqlScanWithoutRedisOrExplicitScan() throws Exception {
        try (var receiver = new Receiver(false); var context = start(true)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var executor = context.getBean(BoundedDeliveryExecutor.class);
            List<BoundedDeliveryExecutor.Reservation> held = new ArrayList<>();
            try {
                for (int i = 0; i < 20; i++) held.add(executor.reserve().orElseThrow());
                long ep = endpoint(context); long task = create(context, ep); disable(context, ep);
                assertEquals("PAUSED", state(jdbc, task)); enable(context, ep);
                consistent(jdbc, task, "PENDING"); assertEquals(0, receiver.requests.get()); assertEquals(0, attemptCount(jdbc, task));
                held.forEach(BoundedDeliveryExecutor.Reservation::close); held.clear();
                await(() -> "SUCCESS".equals(state(jdbc, task)));
                consistent(jdbc, task, "SUCCESS"); assertEquals(1, receiver.requests.get()); assertEquals(1, attemptCount(jdbc, task));
                assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?", String.class, task));
                assertEquals(200, jdbc.queryForObject("SELECT http_status FROM delivery_attempt WHERE task_id=?", Integer.class, task));
            } finally { held.forEach(BoundedDeliveryExecutor.Reservation::close); }
        }
    }

    @Test void expiredStartedPausesThenEnableAndFormalHttpRetryPreserveUnknownHistory() throws Exception {
        try (var receiver = new Receiver(false); var context = start(false)) {
            var jdbc = context.getBean(JdbcTemplate.class); long ep = endpoint(context); long task = create(context, ep);
            var claim = context.getBean(DeliveryClaimService.class).claim(task).orElseThrow();
            // 模拟领取提交后执行器退出，STARTED 未取得可确认的 HTTP 结果。
            disable(context, ep); CLOCK.setInstant(claim.leaseUntil().toInstant(ZoneOffset.UTC));
            assertTrue(context.getBean(DeliveryRecoveryService.class).recover(task, claim.claimToken()));
            consistent(jdbc, task, "PAUSED");
            var unknown = jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=?", task);
            assertEquals("UNKNOWN", unknown.get("result")); assertEquals("EXECUTOR_INTERRUPTED", unknown.get("error_code"));
            assertNull(unknown.get("http_status")); assertNull(unknown.get("response_excerpt"));
            LocalDateTime due = time(jdbc, task, "resume_due_at"); assertEquals(claim.leaseUntil().plusSeconds(10), due);
            scanRepeatedly(context); assertEquals(0, receiver.requests.get());
            enable(context, ep); consistent(jdbc, task, "WAIT_RETRY"); assertEquals(due, time(jdbc, task, "next_run_at"));
            CLOCK.setInstant(due.toInstant(ZoneOffset.UTC).minusMillis(1)); scanRepeatedly(context);
            assertEquals(0, receiver.requests.get()); assertEquals(1, attemptCount(jdbc, task));
            CLOCK.setInstant(due.toInstant(ZoneOffset.UTC)); scanRepeatedly(context); await(() -> "SUCCESS".equals(state(jdbc, task)));
            consistent(jdbc, task, "SUCCESS"); assertEquals(1, receiver.requests.get()); assertEquals(2, attemptCount(jdbc, task));
            assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?", Integer.class, task));
            assertEquals(List.of("UNKNOWN", "SUCCESS"), jdbc.queryForList("SELECT result FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", String.class, task));
            assertEquals(200, jdbc.queryForObject("SELECT http_status FROM delivery_attempt WHERE task_id=? AND attempt_no=2", Integer.class, task));
            DeliveryRedeliveryIntegrationTests.same(unknown, jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=1", task));
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(task, 1, claim.claimToken(),
                    com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult.fromHttpResponse(200, "late")));
            assertFalse(context.getBean(DeliveryRecoveryService.class).recover(task, claim.claimToken()));
            consistent(jdbc, task, "SUCCESS");
        }
    }

    static void assertSnapshot(Map<String,Object> before, Map<String,Object> after) {
        for (String column : List.of("target_type_snapshot", "url_snapshot", "timeout_ms_snapshot", "max_attempts", "retry_delay_1_s", "retry_delay_2_s", "attempt_count"))
            assertEquals(before.get(column), after.get(column), column);
        assertArrayEquals((byte[]) before.get("secret_ciphertext_snapshot"), (byte[]) after.get("secret_ciphertext_snapshot"));
    }
    static void consistent(JdbcTemplate jdbc, long task, String expected) {
        assertEquals(expected, state(jdbc, task));
        assertEquals(expected, jdbc.queryForObject("SELECT e.current_status FROM event e JOIN delivery_task t ON e.id=t.event_pk WHERE t.id=? AND e.latest_round_no=t.round_no", String.class, task));
    }
    static long actor(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT id FROM app_user WHERE username='enable-delivery-admin'", Long.class); }
    static long endpoint(ConfigurableApplicationContext context) {
        return context.getBean(EndpointSecretProvisioningService.class).createSimulator("enable-delivery", 5000, true, actor(context.getBean(JdbcTemplate.class)), id()).id();
    }
    static long create(ConfigurableApplicationContext context, long ep) {
        String eventId = id();
        context.getBean(EventCreationService.class).create(eventId, "order.paid", ep, new EventPayloadCanonicalizer().canonicalize(JsonNodeFactory.instance.nullNode())).orElseThrow();
        return context.getBean(JdbcTemplate.class).queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=?", Long.class, eventId);
    }
    static void disable(ConfigurableApplicationContext context, long ep) { context.getBean(EndpointDisableService.class).disable(ep, actor(context.getBean(JdbcTemplate.class)), id()).orElseThrow(); }
    static void enable(ConfigurableApplicationContext context, long ep) { context.getBean(EndpointEnableService.class).enable(ep, actor(context.getBean(JdbcTemplate.class)), id()).orElseThrow(); }
    static String id() { return UUID.randomUUID().toString(); }
    static String state(JdbcTemplate jdbc, long task) { return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?", String.class, task); }
    static long attemptCount(JdbcTemplate jdbc, long task) { return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?", Long.class, task); }
    static LocalDateTime time(JdbcTemplate jdbc, long task, String column) { return jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task); }
    static void scanRepeatedly(ConfigurableApplicationContext context) { for (int i = 0; i < 5; i++) context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
    static ConfigurableApplicationContext start(boolean scheduled) {
        CLOCK.setInstant(Instant.parse("2026-10-29T14:00:00.123Z"));
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=enable-delivery-admin", "--HOOKFLOW_ADMIN_PASSWORD=enable-delivery-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key), "--HOOKFLOW_REDIS_ENABLED=false"));
        if (!scheduled) args.add("--spring.profiles.active=p2-login-test");
        return new SpringApplicationBuilder(HookflowBackendApplication.class, ReceiverConfiguration.class).run(args.toArray(String[]::new));
    }

    static class Receiver implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        volatile long delayMillis;
        Receiver(boolean failFirst) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext(SimulatorInternalTargetRoute.PATH, exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes(); int attempt = requests.incrementAndGet();
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    byte[] body = "controlled-test-receiver".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(failFirst && attempt == 1 ? 500 : 200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start(); receiverPort = server.getAddress().getPort();
        }
        @Override public void close() throws Exception {
            server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
