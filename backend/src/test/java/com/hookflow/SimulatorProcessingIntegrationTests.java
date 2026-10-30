package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.DeliveryRedeliveryService;
import com.hookflow.delivery.application.DueDeliveryScanner;
import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.simulator.application.SimulatorProcessingService;
import com.hookflow.simulator.application.SimulatorResponseDelay;
import com.hookflow.simulator.domain.SimulatorConfiguration.Decision;
import com.hookflow.simulator.infrastructure.SimulatorRepository;
import com.hookflow.support.MutableClock;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
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
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class SimulatorProcessingIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant NOW = Instant.parse("2026-10-30T02:00:00.123456Z");
    static final byte[] BODY = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock simulatorClock() { return new MutableClock(NOW); }
        @Bean @Primary ObservedRepository observedRepository(JdbcTemplate jdbc) { return new ObservedRepository(jdbc); }
        @Bean @Primary ObservedDelay observedDelay() { return new ObservedDelay(); }
        @Bean RouteProbe simulatorRouteProbe() { return new RouteProbe(); }
        @Bean org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> simulatorRouteDiagnostics(RouteProbe probe) {
            return factory -> factory.addEngineValves(probe);
        }
    }

    static class RouteProbe extends org.apache.catalina.valves.ValveBase {
        final java.util.concurrent.ArrayBlockingQueue<String> requests = new java.util.concurrent.ArrayBlockingQueue<>(128);
        RouteProbe() { super(true); }
        @Override public void invoke(org.apache.catalina.connector.Request request, org.apache.catalina.connector.Response response) throws java.io.IOException, jakarta.servlet.ServletException {
            try { getNext().invoke(request, response); }
            finally {
                requests.offer("localPort=" + request.getLocalPort() + " serverPort=" + request.getServerPort()
                        + " status=" + response.getStatus() + " contextMapped=" + (request.getContext() != null)
                        + " wrapperMapped=" + (request.getWrapper() != null));
            }
        }
    }

    static class ObservedRepository extends SimulatorRepository {
        final AtomicInteger firstProcessingWrites = new AtomicInteger();
        ObservedRepository(JdbcTemplate jdbc) { super(jdbc); }
        @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
        public int writes() { return firstProcessingWrites.get(); }
        @Override public void processOnce(String id, LocalDateTime now) {
            super.processOnce(id, now);
            firstProcessingWrites.incrementAndGet();
        }
    }

    static class ObservedDelay extends SimulatorResponseDelay {
        final CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        @Override public void await(int delayMs) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (delayMs == 0) { super.await(0); return; }
            started.countDown();
            try { super.await(delayMs); } finally { finished.countDown(); }
        }
    }

    @AfterEach void clear() {
        var jdbc = independent();
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config",
                "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint")) jdbc.update("DELETE FROM " + table);
    }

    @Test void emptyDatabaseInitializesOnlyDefaultAndSuccessLogsUtcAndNeverProcessesTwiceAcrossModeChanges() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(0, f.count("simulator_config"));
            String id = f.event(5000); long audits = f.count("audit_log");
            assertEquals(200, f.signed(id).statusCode());
            assertEquals("ALWAYS_200", f.jdbc.queryForObject("SELECT mode FROM simulator_config", String.class));
            assertEquals(f.now(), f.jdbc.queryForObject("SELECT updated_at FROM simulator_config", LocalDateTime.class));
            var original = f.state(id); assertEquals(f.now(), original.get("processed_at"));
            assertEquals(1, original.get("received_count"));
            for (String mode : List.of("ALWAYS_500", "FAIL_FIRST_N", "PROCESSED_THEN_DELAY", "ALWAYS_200")) {
                f.configure(mode, mode.equals("FAIL_FIRST_N") ? 10 : 0, mode.equals("PROCESSED_THEN_DELAY") ? 30000 : 0);
                f.clock.advance(Duration.ofMillis(1));
                long began = System.nanoTime(); assertEquals(200, f.signed(id).statusCode());
                assertTrue(Duration.ofNanos(System.nanoTime() - began).toMillis() < 1500, "重复请求不能等待模式延迟");
                assertEquals(original.get("processed_at"), f.state(id).get("processed_at"));
            }
            assertEquals(5, f.state(id).get("received_count")); assertEquals(1, f.writes());
            var logs = f.logs(id); assertEquals(5, logs.size());
            for (int i = 0; i < logs.size(); i++) {
                assertEquals(i == 0 ? 0 : 1, number(logs.get(i), "duplicate_processed"));
                assertEquals(200, number(logs.get(i), "planned_status")); assertEquals(1, number(logs.get(i), "signature_valid"));
            }
            assertEquals(List.of("ALWAYS_200", "ALWAYS_500", "FAIL_FIRST_N", "PROCESSED_THEN_DELAY", "ALWAYS_200"),
                    logs.stream().map(row -> row.get("config_mode")).toList());
            assertEquals(audits, f.count("audit_log"));
        }
    }

    @Test void failFirstNIncludesZeroAndTenAndKeepsDifferentEventsIndependent() throws Exception {
        try (var f = new Fixture()) {
            for (int n : new int[] {0, 2, 10}) {
                f.configure("FAIL_FIRST_N", n, 0);
                String a = f.event(5000), b = f.event(5000);
                for (int count = 1; count <= n + 1; count++) {
                    for (String id : List.of(a, b)) {
                        assertEquals(count <= n ? 500 : 200, f.signed(id).statusCode());
                        assertEquals(count, f.state(id).get("received_count"));
                        if (count <= n) assertNull(f.state(id).get("processed_at"));
                        else assertEquals(f.now(), f.state(id).get("processed_at"));
                    }
                }
                assertEquals(n + 1, f.logs(a).size()); assertEquals(n + 1, f.logs(b).size());
            }
            assertEquals(6, f.writes());
        }
    }

    @Test void formalAlways200AndFailFirstTwoUseRealRouteAndSavedTenThirtySecondPolicy() throws Exception {
        try (var f = new Fixture()) {
            for (int n : new int[] {0, 2}) {
                f.configure(n == 0 ? "ALWAYS_200" : "FAIL_FIRST_N", n, 0);
                String id = f.event(5000); long task = f.task(id); long audits = f.count("audit_log");
                for (int attempt = 1; attempt <= n + 1; attempt++) {
                    f.scan(); f.awaitTask(task, attempt <= n ? "WAIT_RETRY" : "SUCCESS");
                    f.assertAttempt(task, attempt, attempt <= n ? "HTTP_FAILURE" : "SUCCESS", attempt <= n ? 500 : 200);
                    assertEquals(attempt, f.state(id).get("received_count"));
                    assertEquals(attempt, f.logs(id).size());
                    if (attempt <= n) {
                        assertNull(f.state(id).get("processed_at"));
                        f.advanceToRetry(task, attempt, attempt == 1 ? 10 : 30);
                    }
                }
                f.consistent(task, "SUCCESS", n + 1);
                assertEquals(audits, f.count("audit_log"));
                assertEquals(1L, f.logs(id).stream().filter(row -> number(row, "planned_status") == 200).count());
            }
            assertEquals(2, f.writes());
        }
    }

    @Test void formalAlways500ExhaustsRoundThenManualRoundUsesSamePersistentBusinessIdentity() throws Exception {
        try (var f = new Fixture()) {
            f.configure("ALWAYS_500", 0, 0); String id = f.event(5000); long task = f.task(id);
            for (int attempt = 1; attempt <= 3; attempt++) {
                f.scan(); f.awaitTask(task, attempt == 3 ? "FINAL_FAILED" : "WAIT_RETRY");
                f.assertAttempt(task, attempt, "HTTP_FAILURE", 500);
                assertNull(f.state(id).get("processed_at"));
                if (attempt < 3) f.advanceToRetry(task, attempt, attempt == 1 ? 10 : 30);
            }
            f.consistent(task, "FINAL_FAILED", 3); f.scan(); assertEquals(3, f.logs(id).size());
            var old = f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", task);
            f.configure("FAIL_FIRST_N", 3, 0);
            long actor = f.jdbc.queryForObject("SELECT id FROM app_user WHERE username='p802-admin'", Long.class);
            f.context.getBean(DeliveryRedeliveryService.class).redeliver(id, actor, UUID.randomUUID().toString());
            long next = f.task(id); assertNotEquals(task, next); f.scan(); f.awaitTask(next, "SUCCESS");
            f.consistent(next, "SUCCESS", 1); assertEquals(4, f.state(id).get("received_count")); assertEquals(1, f.writes());
            assertEquals(old, f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", task));
            assertEquals(List.of(500, 500, 500, 200), f.logs(id).stream().map(row -> number(row, "planned_status")).toList());
        }
    }

    @Test void processedThenDelayCommitsBeforeRealTimeoutAndRetrySucceedsWhileOriginalResponseStillWaits() throws Exception {
        try (var f = new Fixture()) {
            f.configure("PROCESSED_THEN_DELAY", 0, 3500); String id = f.event(1200); long task = f.task(id);
            var delay = f.context.getBean(ObservedDelay.class); f.scan();
            assertTrue(delay.started.await(3, TimeUnit.SECONDS));
            var independent = independent();
            assertNotNull(independent.queryForObject("SELECT processed_at FROM simulator_event_state WHERE event_id=?", LocalDateTime.class, id));
            assertEquals(1L, independent.queryForObject("SELECT COUNT(*) FROM simulator_request_log WHERE event_id=? AND planned_status=200", Long.class, id));
            var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            try (var connection = ds.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT received_count FROM simulator_event_state WHERE event_id=? FOR UPDATE NOWAIT")) {
                    statement.setQueryTimeout(2); statement.setString(1, id);
                    try (var result = statement.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
                } finally { connection.rollback(); }
            }
            f.awaitTask(task, "WAIT_RETRY"); f.assertAttempt(task, 1, "UNKNOWN", null);
            var first = f.jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=1", task);
            assertNull(first.get("response_excerpt"));
            assertEquals(1L, delay.finished.getCount(), "首个响应仍在真实延迟中");
            long began = System.nanoTime(); assertEquals(200, f.signed(id).statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).toMillis() < 900);
            assertEquals(1L, delay.finished.getCount());
            f.advanceToRetry(task, 1, 10); f.scan(); f.awaitTask(task, "SUCCESS");
            f.assertAttempt(task, 2, "SUCCESS", 200); f.consistent(task, "SUCCESS", 2);
            assertEquals(1L, delay.finished.getCount(), "正式重试应在原响应延迟结束前完成");
            assertEquals(3, f.state(id).get("received_count")); assertEquals(1, f.writes());
            assertEquals(List.of(0, 1, 1), f.logs(id).stream().map(row -> number(row, "duplicate_processed")).toList());
            assertTrue(f.logs(id).stream().allMatch(row -> number(row, "planned_status") == 200));
            assertTrue(delay.finished.await(5, TimeUnit.SECONDS));
            assertEquals(first, f.jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=1", task));
        }
    }

    @Test void databaseLogAndProcessingFailuresRollbackCountStateAndNecessaryRecordInsteadOfFakeSuccess() throws Exception {
        try (var f = new Fixture()) {
            f.configure("ALWAYS_200", 0, 0); String id = f.event(5000);
            f.jdbc.execute("ALTER TABLE simulator_request_log ADD CONSTRAINT p802_reject_log CHECK (signature_valid=0)");
            try {
                var response = f.signed(id); f.safeError(response, 500);
                assertEquals(0, f.count("simulator_event_state")); assertEquals(0, f.count("simulator_request_log"));
                // 拒绝记录不可用时仍保持原 401；不能把认证失败伪装成处理失败或成功。
                f.jdbc.execute("ALTER TABLE simulator_request_log DROP CHECK p802_reject_log");
                f.jdbc.execute("ALTER TABLE simulator_request_log ADD CONSTRAINT p802_reject_log CHECK (signature_valid=1)");
                f.safeError(f.invalid(id), 401); assertEquals(0, f.count("simulator_event_state"));
            } finally { f.jdbc.execute("ALTER TABLE simulator_request_log DROP CHECK p802_reject_log"); }
            f.configure("ALWAYS_500", 0, 0); assertEquals(500, f.signed(id).statusCode());
            var state = f.state(id); var logs = f.logs(id);
            f.configure("ALWAYS_200", 0, 0);
            f.jdbc.execute("ALTER TABLE simulator_event_state ADD CONSTRAINT p802_reject_processing CHECK (processed_at IS NULL)");
            try { f.safeError(f.signed(id), 500); assertEquals(state, f.state(id)); assertEquals(logs, f.logs(id)); }
            finally { f.jdbc.execute("ALTER TABLE simulator_event_state DROP CHECK p802_reject_processing"); }
            assertEquals(200, f.signed(id).statusCode()); assertEquals(2, f.state(id).get("received_count"));
        }
    }

    @Test void validReceptionLogFailureRollsBackExistingUnprocessedAndProcessedCounters() throws Exception {
        try (var f = new Fixture()) {
            f.configure("ALWAYS_500", 0, 0); String pending = f.event(5000);
            assertEquals(500, f.signed(pending).statusCode());
            f.configure("ALWAYS_200", 0, 0); String done = f.event(5000); assertEquals(200, f.signed(done).statusCode());
            var pendingState = f.state(pending); var doneState = f.state(done);
            var pendingLogs = f.logs(pending); var doneLogs = f.logs(done);
            // 用未来时间的 CHECK 只拒绝本次新插入，保留已有记录。
            f.jdbc.execute("ALTER TABLE simulator_request_log ADD CONSTRAINT p802_reject_existing_log CHECK (received_at <= '2026-10-30 02:00:00.123')");
            try {
                f.clock.advance(Duration.ofMillis(1));
                f.safeError(f.signed(pending), 500); f.safeError(f.signed(done), 500);
                assertEquals(pendingState,f.state(pending)); assertEquals(doneState,f.state(done));
                assertEquals(pendingLogs,f.logs(pending)); assertEquals(doneLogs,f.logs(done));
            } finally { f.jdbc.execute("ALTER TABLE simulator_request_log DROP CHECK p802_reject_existing_log"); }
        }
    }

    @Test void completeApplicationContextRestartPreservesFailCountProcessedMarkerAndAllLogs() throws Exception {
        try (var f = new Fixture()) {
            f.configure("FAIL_FIRST_N", 2, 0); String id = f.event(5000);
            assertEquals(500, f.signed(id).statusCode()); var first = f.logs(id);
            f.restart(); assertEquals(first, f.logs(id)); assertEquals(1, f.state(id).get("received_count"));
            assertEquals(500, f.signed(id).statusCode()); assertNull(f.state(id).get("processed_at"));
            assertEquals(200, f.signed(id).statusCode()); var processed = f.state(id).get("processed_at");
            var logs = f.logs(id); f.restart(); f.configure("ALWAYS_500", 0, 0);
            assertEquals(200, f.signed(id).statusCode()); assertEquals(4, f.state(id).get("received_count"));
            assertEquals(processed, f.state(id).get("processed_at")); assertEquals(0, f.writes());
            assertEquals(logs, f.logs(id).subList(0, 3)); assertEquals(1, number(f.logs(id).getLast(), "duplicate_processed"));
        }
    }

    @Test void realUniqueKeyAndRowLockCompetitionSerializesFirstFailuresFirstSuccessAndDuplicates() throws Exception {
        try (var f = new Fixture()) {
            f.configure("ALWAYS_200", 0, 0); String fresh = f.event(5000);
            var first = ordered(f, fresh); assertEquals(200, first.plannedStatus()); assertTrue(first.duplicateProcessed());
            assertEquals(2, f.state(fresh).get("received_count")); assertEquals(1, f.writes());
            var processed = f.state(fresh).get("processed_at");
            f.configure("FAIL_FIRST_N", 2, 0); String failing = f.event(5000);
            assertEquals(500, ordered(f, failing).plannedStatus());
            assertEquals(2, f.state(failing).get("received_count")); assertNull(f.state(failing).get("processed_at"));
            assertTrue(ordered(f, failing).duplicateProcessed());
            assertEquals(4, f.state(failing).get("received_count")); assertEquals(2, f.writes());
            f.configure("ALWAYS_500", 0, 0); assertTrue(ordered(f, fresh).duplicateProcessed());
            assertEquals(processed, f.state(fresh).get("processed_at")); assertEquals(2, f.writes());
            assertEquals(List.of(500, 500, 200, 200), f.logs(failing).stream().map(row -> number(row, "planned_status")).toList());
            assertEquals(1L, f.logs(failing).stream().filter(row -> number(row, "planned_status") == 200 && number(row, "duplicate_processed") == 0).count());
        }
    }

    @Test void eachRequestKeepsConfigurationReadBeforeWaitingForEventLock() throws Exception {
        try (var f = new Fixture()) {
            f.configure("FAIL_FIRST_N", 2, 0); String id = f.event(5000);
            Decision second = ordered(f, id, () -> f.configure("ALWAYS_200", 0, 0));
            assertEquals(500, second.plannedStatus()); assertNull(f.state(id).get("processed_at"));
            assertEquals(List.of("FAIL_FIRST_N", "FAIL_FIRST_N"), f.logs(id).stream().map(row -> row.get("config_mode")).toList());
            assertEquals(200, f.signed(id).statusCode());
            assertEquals("ALWAYS_200", f.logs(id).getLast().get("config_mode")); assertEquals(1, f.writes());
        }
    }

    @Test void repeatedColdContextsAndRestartsKeepFormalLoginAndEndpointRoutesOnTheirOwnPort() throws Exception {
        for (int cycle = 0; cycle < 8; cycle++) {
            try (var fixture = new Fixture()) {
                assertNotNull(fixture.event(5000));
                fixture.restart();
                assertNotNull(fixture.event(5000));
            }
        }
    }

    @Test void concurrentEmptyConfigurationInitializationUsesRealSingletonConstraintWithoutOverwritingWinner() throws Exception {
        try (var f = new Fixture()) {
            String a = f.event(5000), b = f.event(5000);
            var release = new CountDownLatch(1); var ready = new CountDownLatch(1);
            var blocker = new AtomicReference<Long>(); var waiter = new AtomicReference<Long>();
            var workers = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
            Future<?> first = workers.submit(() -> f.transaction().execute(tx -> {
                blocker.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.processing().receiveVerified(a); ready.countDown();
                try { assertTrue(release.await(8,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertTrue(ready.await(4,TimeUnit.SECONDS));
                Future<?> second = workers.submit(() -> f.transaction().execute(tx -> {
                    waiter.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                    return f.processing().receiveVerified(b);
                }));
                var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
                observer.setQueryTimeout(2);
                // 首次 INSERT 的隐式锁可能由等待者物化，归属以 InnoDB 事务 ID 而非锁行 THREAD_ID 判定。
                await(() -> waiter.get() != null && observer.queryForObject(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                                + "JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE "
                                + "JOIN information_schema.innodb_trx rt ON rt.trx_id=w.REQUESTING_ENGINE_TRANSACTION_ID "
                                + "JOIN information_schema.innodb_trx bt ON bt.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID "
                                + "WHERE rt.trx_mysql_thread_id=? AND bt.trx_mysql_thread_id=? AND r.OBJECT_NAME='simulator_config' "
                                + "AND r.INDEX_NAME='PRIMARY' AND r.LOCK_TYPE='RECORD' AND r.LOCK_DATA='1'",
                        Long.class, waiter.get(), blocker.get()) > 0, 3000);
                assertFalse(second.isDone());
                assertEquals(0,independent().queryForObject("SELECT COUNT(*) FROM simulator_config",Integer.class));
                release.countDown(); first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
                assertEquals(1,f.count("simulator_config")); assertEquals(2,f.count("simulator_event_state"));
                assertEquals(2,f.count("simulator_request_log")); assertEquals(2,f.writes());
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(8,TimeUnit.SECONDS)); }
        }
    }

    private static Decision ordered(Fixture f, String id) throws Exception {
        return ordered(f, id, () -> { });
    }

    private static Decision ordered(Fixture f, String id, Runnable whileBlocked) throws Exception {
        CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        var workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        var blocker = new AtomicReference<Long>(); var waiter = new AtomicReference<Long>();
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        observer.setQueryTimeout(2);
        int before = f.jdbc.queryForObject("SELECT COALESCE(SUM(received_count),0) FROM simulator_event_state WHERE event_id=?", Integer.class, id);
        Future<?> winner = workers.submit(() -> f.transaction().execute(tx -> {
            blocker.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            var result = f.processing().receiveVerified(id); ready.countDown();
            try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return result;
        }));
        try {
            assertTrue(ready.await(4, TimeUnit.SECONDS));
            Future<Decision> loser = workers.submit(() -> f.transaction().execute(tx -> {
                waiter.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                return f.processing().receiveVerified(id);
            }));
            await(() -> waiter.get() != null && observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE "
                            + "JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND b.ENGINE=w.ENGINE "
                            + "JOIN performance_schema.threads rt ON rt.THREAD_ID=w.REQUESTING_THREAD_ID "
                            + "JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID "
                            + "WHERE rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=? AND r.OBJECT_NAME='simulator_event_state' "
                            + "AND r.INDEX_NAME='PRIMARY' AND r.LOCK_TYPE='RECORD' AND r.LOCK_DATA=? "
                            + "AND b.OBJECT_NAME=r.OBJECT_NAME AND b.INDEX_NAME=r.INDEX_NAME AND b.LOCK_DATA=r.LOCK_DATA",
                    Long.class, waiter.get(), blocker.get(), "'" + id + "'") > 0, 3000);
            assertFalse(loser.isDone());
            assertEquals(before, independent().queryForObject("SELECT COALESCE(SUM(received_count),0) FROM simulator_event_state WHERE event_id=?", Integer.class, id));
            var beforeLogs = independent().queryForList("SELECT * FROM simulator_request_log WHERE event_id=? ORDER BY id", id);
            assertTrue(beforeLogs.stream().noneMatch(row -> number(row, "duplicate_processed") == 1 && number(row, "planned_status") != 200));
            whileBlocked.run();
            release.countDown(); winner.get(5, TimeUnit.SECONDS); return loser.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(8, TimeUnit.SECONDS)); }
    }

    static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean flag ? (flag ? 1 : 0) : ((Number) value).intValue();
    }
    static JdbcTemplate independent() { return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())); }
    static void await(BooleanSupplier condition, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "有界等待未达成数据库/HTTP条件");
    }

    static class Fixture implements AutoCloseable {
        final String key;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ConfigurableApplicationContext context; JdbcTemplate jdbc; JsonMapper mapper; MutableClock clock;
        String base, token;
        Fixture() throws Exception {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); key = Base64.getEncoder().encodeToString(bytes);
            try { start(); }
            catch (Exception | Error failure) { client.close(); if (context != null) context.close(); throw failure; }
        }
        void start() throws Exception {
            context = new SpringApplicationBuilder(HookflowBackendApplication.class, Configuration.class).run(
                    "--spring.profiles.active=p2-login-test", "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p802-admin", "--HOOKFLOW_ADMIN_PASSWORD=p802-admin-password", "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + key,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=4");
            jdbc = context.getBean(JdbcTemplate.class); mapper = context.getBean(JsonMapper.class); clock = context.getBean(MutableClock.class);
            base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            var login = api("/api/v1/auth/login", "{\"username\":\"p802-admin\",\"password\":\"p802-admin-password\"}", false);
            assertEquals(200, login.statusCode(), () -> diagnostic("login", login));
            token = mapper.readTree(login.body()).path("token").asString();
            assertNotNull(token, "成功登录必须包含Token");
            assertFalse(token.isBlank(), "成功登录必须包含非空Token");
        }
        String diagnostic(String operation, HttpResponse<String> response) {
            var web = (org.springframework.boot.web.server.context.WebServerApplicationContext) context;
            var mappings = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            boolean endpointRoute = mappings.getHandlerMethods().values().stream().anyMatch(method -> method.getBeanType().getName().equals("com.hookflow.endpoint.api.EndpointController"));
            var servlet = ((org.springframework.web.context.WebApplicationContext) context).getServletContext();
            String requestId = response.headers().firstValue("X-Request-Id").filter(value -> value.matches("[0-9a-f-]{36}")).orElse("absent");
            return operation + " status=" + response.statusCode() + " active=" + context.isActive()
                    + " configuredPort=" + URI.create(base).getPort() + " actualPort=" + web.getWebServer().getPort()
                    + " responsePort=" + response.uri().getPort() + " endpointRoute=" + endpointRoute
                    + " dispatcherRegistered=" + (servlet.getServletRegistration("dispatcherServlet") != null)
                    + " requestId=" + requestId + " httpVersion=" + response.version()
                    + " bodyBytes=" + response.body().getBytes(StandardCharsets.UTF_8).length
                    + " engineRequests=" + context.getBean(RouteProbe.class).requests
                    + " proxy=" + client.proxy().map(value -> value.getClass().getName()).orElse("default")
                    + " selectedProxyTypes=" + java.net.ProxySelector.getDefault().select(URI.create(base)).stream().map(value -> value.type().name()).toList();
        }
        void restart() throws Exception { context.close(); start(); }
        void configure(String mode, int n, int ms) {
            // 仅 Testcontainers 临时数据库准备配置；不是 P8-03 管理接口或正式配置写入能力。
            jdbc.update("INSERT INTO simulator_config (id,mode,fail_first_n,delay_ms,updated_at) VALUES (1,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE mode=?,fail_first_n=?,delay_ms=?,updated_at=?", mode,n,ms,now(),mode,n,ms,now());
        }
        String event(int timeout) throws Exception {
            var endpoint = api("/api/v1/admin/endpoints", "{\"name\":\"p802-" + UUID.randomUUID() + "\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":" + timeout + "}", true);
            assertEquals(201, endpoint.statusCode(), () -> diagnostic("create-endpoint", endpoint));
            var event = api("/api/v1/admin/events/test", "{\"eventType\":\"order.paid\",\"endpointId\":" + mapper.readTree(endpoint.body()).path("id").asLong() + ",\"payload\":{\"amount\":1}}", true);
            assertEquals(202, event.statusCode(), event.body()); return mapper.readTree(event.body()).path("eventId").asString();
        }
        HttpResponse<String> api(String path, String body, boolean admin) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json");
            if (admin) request.header("Authorization", "Bearer " + token);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> signed(String id) throws Exception { return receive(id, false); }
        HttpResponse<String> invalid(String id) throws Exception { return receive(id, true); }
        HttpResponse<String> receive(String id, boolean invalid) throws Exception {
            String secret = context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(jdbc.queryForObject("SELECT secret_ciphertext_snapshot FROM delivery_task WHERE id=?", (rs,row) -> rs.getBytes(1),task(id)));
            String timestamp = clock.instant().toString(); String signature = context.getBean(DeliveryRequestSigner.class).sign(id, timestamp, BODY, secret);
            return client.send(HttpRequest.newBuilder(URI.create(base + SimulatorInternalTargetRoute.PATH)).timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json").header("X-Request-Id", "forged-p802")
                    .header("X-HookFlow-Event-Id", id).header("X-HookFlow-Timestamp", timestamp).header("X-HookFlow-Signature", invalid ? "0".repeat(64) : signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(BODY)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void safeError(HttpResponse<String> response, int status) throws Exception {
            assertEquals(status,response.statusCode(),response.body()); var body = mapper.readTree(response.body()); assertEquals(3,body.size());
            String requestId=response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(requestId).toString(),requestId);
            assertEquals(requestId,body.path("requestId").asString()); assertNotEquals("forged-p802",requestId);
            assertEquals(status==401 ? "UNAUTHORIZED" : "INTERNAL_ERROR",body.path("code").asString());
            for (String unsafe : List.of(token,key,"CHECK","p802_reject","Exception",new String(BODY,StandardCharsets.UTF_8))) assertFalse(response.body().contains(unsafe));
        }
        LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),ZoneOffset.UTC); }
        Map<String,Object> state(String id) { return jdbc.queryForMap("SELECT * FROM simulator_event_state WHERE event_id=?",id); }
        List<Map<String,Object>> logs(String id) { return jdbc.queryForList("SELECT * FROM simulator_request_log WHERE event_id=? ORDER BY id",id); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Integer.class); }
        int writes() { return context.getBean(ObservedRepository.class).writes(); }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no",Long.class,id); }
        void scan() { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
        void awaitTask(long task,String status) throws Exception { await(() -> status.equals(jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,task)),5000); }
        void assertAttempt(long task,int no,String result,Integer status) {
            var row=jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=?",task,no);
            assertEquals(result,row.get("result")); assertEquals(status,row.get("http_status")); assertNotNull(row.get("ended_at"));
        }
        void advanceToRetry(long task,int attempt,int seconds) throws Exception {
            LocalDateTime ended=jdbc.queryForObject("SELECT ended_at FROM delivery_attempt WHERE task_id=? AND attempt_no=?",LocalDateTime.class,task,attempt);
            LocalDateTime due=jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?",LocalDateTime.class,task);
            assertEquals(ended.plusSeconds(seconds),due);
            clock.setInstant(due.toInstant(ZoneOffset.UTC).minusMillis(1)); scan();
            assertEquals(attempt,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Integer.class,task));
            clock.setInstant(due.toInstant(ZoneOffset.UTC));
        }
        void consistent(long task,String status,int count) {
            assertEquals(status,jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,task));
            assertEquals(status,jdbc.queryForObject("SELECT current_status FROM event WHERE id=(SELECT event_pk FROM delivery_task WHERE id=?)",String.class,task));
            assertEquals(count,jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?",Integer.class,task));
            assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Integer.class,task));
        }
        SimulatorProcessingService processing() { return context.getBean(SimulatorProcessingService.class); }
        TransactionTemplate transaction() {
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(12); return tx;
        }
        @Override public void close() { client.close(); context.close(); }
    }
}
