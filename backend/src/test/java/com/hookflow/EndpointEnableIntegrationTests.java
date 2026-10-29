package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.*;
import com.hookflow.support.MutableClock;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class EndpointEnableIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant BASE = Instant.parse("2026-10-29T14:00:00.123456Z");
    static final LocalDateTime NOW = LocalDateTime.ofInstant(BASE.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    static final LocalDateTime DUE = NOW.minusHours(1);
    static final MutableClock CLOCK = new MutableClock(BASE);
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static long actor;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock enableClock() { return CLOCK; }
    }

    @BeforeAll static void start() {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class, TimeConfiguration.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(), "--HOOKFLOW_ADMIN_USERNAME=enable-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=enable-password",
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
        jdbc = context.getBean(JdbcTemplate.class);
        actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='enable-admin'", Long.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void resetClock() { CLOCK.setInstant(BASE); }
    @AfterEach void clear() {
        jdbc.update("DELETE FROM delivery_attempt"); jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM audit_log"); jdbc.update("DELETE FROM endpoint");
    }

    long endpoint() {
        return context.getBean(EndpointSecretProvisioningService.class).createExternal("enable",
                "https://hooks.example.com/old", 5000, true, actor, id()).configuration().id();
    }
    Fixture fixture(long endpoint, String state, LocalDateTime due) {
        String id = id(); boolean terminal = Set.of("SUCCESS", "FINAL_FAILED").contains(state);
        jdbc.update("INSERT INTO event (event_id,event_type,payload_json,payload_fingerprint,endpoint_id,"
                        + "latest_round_no,current_status,created_at,terminal_at) VALUES "
                        + "(?,'order.paid','null',UNHEX(SHA2('null',256)),?,1,?,?,?)",
                id, endpoint, state, DUE, terminal ? DUE : null);
        long event = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, id);
        int count = "PENDING".equals(state) ? 0 : 1;
        jdbc.update("INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,"
                        + "retry_delay_1_s,retry_delay_2_s,next_run_at,lease_until,claim_token,target_type_snapshot,"
                        + "url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at,finished_at) "
                        + "SELECT ?,1,?,?,3,7.125,19.5,?,?,?,'EXTERNAL',url,timeout_ms,signing_secret_ciphertext,?,?,? "
                        + "FROM endpoint WHERE id=?",
                event, state, count, Set.of("PENDING", "WAIT_RETRY").contains(state) ? due : null,
                "DELIVERING".equals(state) ? NOW.plusMinutes(1) : null,
                "DELIVERING".equals(state) ? id() : null, DUE, DUE, terminal ? DUE : null, endpoint);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?", Long.class, event);
        if (count > 0) {
            boolean started = "DELIVERING".equals(state);
            jdbc.update("INSERT INTO delivery_attempt (task_id,attempt_no,result,started_at,ended_at,duration_ms,request_id) "
                            + "VALUES (?,1,?,?,?,?,?)", task, started ? "STARTED" : "HTTP_FAILURE", DUE,
                    started ? null : DUE, started ? null : 0L, id());
        }
        return new Fixture(endpoint, event, task);
    }
    static String id() { return UUID.randomUUID().toString(); }
    static void disable(long ep) { context.getBean(EndpointDisableService.class).disable(ep, actor, id()).orElseThrow(); }
    static void enable(long ep) { context.getBean(EndpointEnableService.class).enable(ep, actor, id()).orElseThrow(); }
    static Map<String,Object> task(Fixture f) { return jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task); }
    static Map<String,Object> endpointRow(long ep) { return jdbc.queryForMap("SELECT * FROM endpoint WHERE id=?", ep); }
    static String state(Fixture f) { return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?", String.class, f.task); }
    static void consistent(Fixture f, String expected) {
        assertEquals(expected, state(f));
        assertEquals(expected, jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, f.event));
        assertEquals(1, jdbc.queryForObject("SELECT latest_round_no FROM event WHERE id=?", Integer.class, f.event));
    }
    static long attempts(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?", Long.class, f.task); }
    static LocalDateTime time(Fixture f, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id=?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), f.task);
    }
    static void snapshotEquals(Map<String,Object> before, Map<String,Object> after) {
        for (String column : List.of("event_pk", "round_no", "attempt_count", "max_attempts", "retry_delay_1_s",
                "retry_delay_2_s", "target_type_snapshot", "url_snapshot", "timeout_ms_snapshot", "created_at")) {
            assertEquals(before.get(column), after.get(column), column);
        }
        assertArrayEquals((byte[]) before.get("secret_ciphertext_snapshot"), (byte[]) after.get("secret_ciphertext_snapshot"));
    }
    static void rowEquals(Map<String,Object> before, Map<String,Object> after) {
        assertEquals(before.keySet(), after.keySet());
        for (String key : before.keySet()) {
            if (before.get(key) instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) after.get(key), key);
            else assertEquals(before.get(key), after.get(key), key);
        }
    }
    static void restored(Fixture f, String status, LocalDateTime due) {
        consistent(f, status); assertEquals(due, time(f, "next_run_at"));
        assertNull(task(f).get("resume_status")); assertNull(time(f, "resume_due_at"));
    }
    static long enabledAudits() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_ENABLED'", Long.class); }

    @Test void restoresOnlyLatestPausedTasksAtCorrectTimeWithoutChangingSnapshotsOrHistory() {
        long ep = endpoint();
        List<Fixture> paused = List.of(fixture(ep, "PENDING", NOW.plusHours(2)), fixture(ep, "PENDING", DUE),
                fixture(ep, "WAIT_RETRY", DUE), fixture(ep, "WAIT_RETRY", NOW.plusHours(3)));
        List<Map<String,Object>> before = paused.stream().map(EndpointEnableIntegrationTests::task).toList();
        List<Fixture> unchanged = List.of(fixture(ep, "DELIVERING", null), fixture(ep, "SUCCESS", null), fixture(ep, "FINAL_FAILED", null));
        List<Map<String,Object>> untouched = unchanged.stream().map(EndpointEnableIntegrationTests::task).toList();
        Fixture history = fixture(ep, "PENDING", DUE);
        disable(ep);
        Map<String,Object> oldPaused = task(history);
        // 历史夹具仅用 SQL 构造，不调用或实现人工重投。
        jdbc.update("INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,retry_delay_1_s,"
                        + "retry_delay_2_s,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,"
                        + "created_at,updated_at,finished_at) SELECT event_pk,2,'SUCCESS',1,max_attempts,retry_delay_1_s,"
                        + "retry_delay_2_s,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,"
                        + "created_at,updated_at,? FROM delivery_task WHERE id=?", NOW, history.task);
        jdbc.update("UPDATE event SET latest_round_no=2,current_status='SUCCESS',terminal_at=? WHERE id=?", NOW, history.event);
        long other = endpoint(); Fixture foreign = fixture(other, "PENDING", DUE); disable(other);
        var foreignBefore = task(foreign); var foreignEndpoint = endpointRow(other);
        long totalAttempts = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class);
        byte[] secret = (byte[]) endpointRow(ep).get("signing_secret_ciphertext");
        context.getBean(EndpointUpdateService.class).update(ep, "edited", "https://hooks.example.com/new", 1234, actor, id()).orElseThrow();
        CLOCK.advance(Duration.ofSeconds(2)); LocalDateTime now = NOW.plusSeconds(2);
        enable(ep);
        for (int i = 0; i < paused.size(); i++) {
            Fixture f = paused.get(i); String status = i < 2 ? "PENDING" : "WAIT_RETRY";
            restored(f, status, i == 3 ? NOW.plusHours(3) : now);
            assertEquals(now, time(f, "updated_at")); snapshotEquals(before.get(i), task(f));
        }
        for (int i = 0; i < unchanged.size(); i++) rowEquals(untouched.get(i), task(unchanged.get(i)));
        rowEquals(oldPaused, task(history)); rowEquals(foreignBefore, task(foreign)); rowEquals(foreignEndpoint, endpointRow(other));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, history.event));
        assertEquals(totalAttempts, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class));
        assertArrayEquals(secret, (byte[]) endpointRow(ep).get("signing_secret_ciphertext"));
        assertEquals("https://hooks.example.com/new", endpointRow(ep).get("url"));
        assertEquals(1234, endpointRow(ep).get("timeout_ms"));
        var endpointBefore = endpointRow(ep); var restored = paused.stream().map(EndpointEnableIntegrationTests::task).toList();
        CLOCK.advance(Duration.ofMinutes(1)); enable(ep);
        rowEquals(endpointBefore, endpointRow(ep));
        for (int i = 0; i < paused.size(); i++) rowEquals(restored.get(i), task(paused.get(i)));
        assertEquals(1, enabledAudits());
        var claims = context.getBean(DeliveryClaimService.class);
        assertTrue(claims.claim(paused.get(0).task).isPresent());
        assertTrue(claims.claim(paused.get(1).task).isPresent());
        assertTrue(claims.claim(paused.get(2).task).isPresent());
        assertTrue(claims.claim(paused.get(3).task).isEmpty());
    }

    @Test void repeatedEnableDoesNotRepairPausedDataOnAlreadyEnabledEndpoint() {
        long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE); disable(ep); enable(ep);
        disable(ep); var paused = task(f);
        // 模拟不一致的历史数据；幂等启用不得暗中进行第二次状态变更。
        jdbc.update("UPDATE endpoint SET enabled=1 WHERE id=?", ep);
        var before = endpointRow(ep); enable(ep);
        rowEquals(paused, task(f)); rowEquals(before, endpointRow(ep)); assertEquals(1, enabledAudits());
        assertTrue(context.getBean(EndpointEnableService.class).enable(Long.MAX_VALUE, actor, id()).isEmpty());
    }

    @Test void taskUpdateFailureRollsBackEarlierRestoredTasks() { rollback("delivery_task", "retry_delay_1_s <> 8.125 OR status = 'PAUSED'"); }
    @Test void eventSyncFailureRollsBackEarlierRestoredEventsAndTasks() { rollback("event", "event_type <> 'enable-reject' OR current_status = 'PAUSED'"); }
    @Test void auditFailureRollsBackEndpointAndAllRestoredTasksAndEvents() { rollback("audit_log", "action <> 'ENDPOINT_ENABLED'"); }
    void rollback(String table, String expression) {
        long ep = endpoint(); Fixture first = fixture(ep, "PENDING", NOW.plusDays(1)), second = fixture(ep, "WAIT_RETRY", DUE);
        if (table.equals("delivery_task")) jdbc.update("UPDATE delivery_task SET retry_delay_1_s=8.125 WHERE id=?", second.task);
        if (table.equals("event")) jdbc.update("UPDATE event SET event_type='enable-reject' WHERE id=?", second.event);
        disable(ep); var before = endpointRow(ep); var firstBefore = task(first); var secondBefore = task(second);
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT ck_enable_failure CHECK (" + expression + ")");
        try {
            assertThrows(RuntimeException.class, () -> enable(ep));
            rowEquals(before, endpointRow(ep)); rowEquals(firstBefore, task(first)); rowEquals(secondBefore, task(second));
            consistent(first, "PAUSED"); consistent(second, "PAUSED");
            assertEquals(0, attempts(first)); assertEquals(1, attempts(second)); assertEquals(0, enabledAudits());
        } finally { jdbc.execute("ALTER TABLE " + table + " DROP CHECK ck_enable_failure"); }
        enable(ep); restored(first, "PENDING", NOW); restored(second, "WAIT_RETRY", NOW);
    }

    @Test void inconsistentEventStateAndStaleClaimFieldsAreNotReportedAsSuccessfulRecovery() {
        for (boolean eventMismatch : List.of(true, false)) {
            long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE); disable(ep);
            if (eventMismatch) jdbc.update("UPDATE event SET current_status='PENDING' WHERE id=?", f.event);
            else jdbc.update("UPDATE delivery_task SET claim_token='stale-token',lease_until=? WHERE id=?", NOW, f.task);
            var before = task(f); var epBefore = endpointRow(ep);
            assertThrows(IllegalStateException.class, () -> enable(ep));
            rowEquals(before, task(f)); rowEquals(epBefore, endpointRow(ep)); assertEquals(0, enabledAudits());
        }
    }

    @Test void repeatedDisableEnableCyclesKeepOriginalRetryDueAndDoNotConsumeAttempts() {
        long ep = endpoint(); Fixture f = fixture(ep, "WAIT_RETRY", NOW.plusSeconds(30));
        for (int i = 0; i < 3; i++) {
            disable(ep); assertEquals(NOW.plusSeconds(30), time(f, "resume_due_at"));
            CLOCK.advance(Duration.ofSeconds(1)); enable(ep);
            restored(f, "WAIT_RETRY", NOW.plusSeconds(30)); assertEquals(1, attempts(f));
            assertEquals(1, task(f).get("attempt_count"));
        }
        assertEquals(3, enabledAudits());
        CLOCK.advance(Duration.ofSeconds(30)); disable(ep); enable(ep);
        restored(f, "WAIT_RETRY", NOW.plusSeconds(33));
    }

    @Test void enableWinsRealLockBeforeRetryableCompletion() throws Exception { completionOrder(true); }
    @Test void completionWinsRealLockAndEnableSeesNewlyCommittedPausedTask() throws Exception { completionOrder(false); }
    void completionOrder(boolean enableFirst) throws Exception {
        long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE);
        var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow(); disable(ep);
        var delivering = task(f); CLOCK.advance(Duration.ofSeconds(1));
        Supplier<Object> enabling = () -> { enable(ep); return true; };
        Supplier<Object> finish = () -> context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(500, "retry"));
        assertEquals(true, ordered(ep, enableFirst ? enabling : finish, enableFirst ? finish : enabling, () -> {
            consistent(f, "DELIVERING"); rowEquals(delivering, task(f)); assertEquals(1, attempts(f));
        }));
        restored(f, "WAIT_RETRY", NOW.plusSeconds(1).plusNanos(7_125_000_000L));
        assertEquals("HTTP_FAILURE", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?", String.class, f.task));
        assertNull(task(f).get("claim_token")); assertNull(time(f, "lease_until"));
        assertEquals(1, task(f).get("attempt_count")); assertEquals(1, attempts(f)); assertEquals(1, enabledAudits());
    }

    @Test void enableUncommittedRecoveryBlocksClaimThenExactlyOneStartedIsCreated() throws Exception {
        long ep = endpoint(); Fixture f = fixture(ep, "PENDING", NOW.plusDays(1)); disable(ep);
        var claims = context.getBean(DeliveryClaimService.class);
        Object result = ordered(ep, () -> { enable(ep); return true; }, () -> claims.claim(f.task), () -> {
            consistent(f, "PAUSED"); assertEquals(0, attempts(f));
            context.getBean(DueDeliveryScanner.class).scanDueTasks(); assertEquals(0, attempts(f));
        });
        assertTrue(((Optional<?>) result).isPresent()); consistent(f, "DELIVERING");
        assertEquals(1, attempts(f)); assertEquals(1, task(f).get("attempt_count"));
        assertEquals("STARTED", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?", String.class, f.task));
        assertTrue(claims.claim(f.task).isEmpty()); assertEquals(1, attempts(f));
    }
    @Test void claimBeforeEnableObservesDisabledAndCanOnlyClaimAfterCommit() throws Exception {
        long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE); disable(ep);
        ordered(ep, () -> { assertTrue(context.getBean(DeliveryClaimService.class).claim(f.task).isEmpty()); return true; },
                () -> { enable(ep); return true; }, () -> { consistent(f, "PAUSED"); assertEquals(0, attempts(f)); });
        restored(f, "PENDING", NOW); assertEquals(0, attempts(f));
        assertTrue(context.getBean(DeliveryClaimService.class).claim(f.task).isPresent()); assertEquals(1, attempts(f));
    }
    @Test void enableDoesNotTouchInFlightLeaseAndExpiryRecoveryStillRejectsLateWriteback() {
        for (boolean recoverFirst : List.of(true, false)) {
            long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE);
            var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow(); disable(ep);
            var active = task(f); var started = jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=?", f.task);
            if (!recoverFirst) {
                enable(ep); rowEquals(active, task(f)); rowEquals(started, jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=?", f.task));
            }
            CLOCK.setInstant(claim.leaseUntil().toInstant(ZoneOffset.UTC));
            assertTrue(context.getBean(DeliveryRecoveryService.class).recover(f.task, claim.claimToken()));
            LocalDateTime retryAt = claim.leaseUntil().plusNanos(7_125_000_000L);
            if (recoverFirst) { consistent(f, "PAUSED"); assertEquals(retryAt, time(f, "resume_due_at")); enable(ep); }
            restored(f, "WAIT_RETRY", retryAt);
            assertEquals("UNKNOWN", jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?", String.class, f.task));
            assertEquals("EXECUTOR_INTERRUPTED", jdbc.queryForObject("SELECT error_code FROM delivery_attempt WHERE task_id=?", String.class, f.task));
            assertFalse(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "late")));
            restored(f, "WAIT_RETRY", retryAt); assertEquals(1, attempts(f)); CLOCK.setInstant(BASE);
        }
    }
    @Test void inFlightSuccessNonRetryableAndExhaustedResultsStayTerminalAfterEnable() {
        for (int status : List.of(200, 404, 500)) {
            long ep = endpoint(); Fixture f = fixture(ep, "PENDING", DUE);
            if (status == 500) jdbc.update("UPDATE delivery_task SET max_attempts=1 WHERE id=?", f.task);
            var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow(); disable(ep); enable(ep);
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(status, "result")));
            consistent(f, status == 200 ? "SUCCESS" : "FINAL_FAILED"); assertEquals(1, attempts(f));
            assertNull(task(f).get("resume_status")); assertNotNull(time(f, "finished_at"));
        }
    }

    Object ordered(long endpoint, Supplier<?> winner, Supplier<?> loser, Runnable whileWaiting) throws Exception {
        CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1), entering = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        var first = workers.submit(() -> transaction().execute(tx -> {
            jdbc.queryForObject("SELECT id FROM endpoint WHERE id=? FOR UPDATE", Long.class, endpoint);
            Object result = winner.get(); changed.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return result;
        }));
        try {
            assertTrue(changed.await(10, TimeUnit.SECONDS));
            var second = workers.submit(() -> { entering.countDown(); return loser.get(); });
            assertTrue(entering.await(5, TimeUnit.SECONDS));
            JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); boolean waiting = false;
            while (System.nanoTime() < deadline) {
                if (root.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                        + "AND l.ENGINE=w.ENGINE WHERE l.OBJECT_NAME='endpoint'", Long.class) > 0) { waiting = true; break; }
                Thread.sleep(10);
            }
            assertTrue(waiting, "必须确认等待真实 MySQL endpoint 行锁后才释放"); assertFalse(second.isDone());
            whileWaiting.run(); release.countDown(); first.get(10, TimeUnit.SECONDS);
            return second.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
    }
    static TransactionTemplate transaction() {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx;
    }
    record Fixture(long endpoint, long event, long task) { }
}
