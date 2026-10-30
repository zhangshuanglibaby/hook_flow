package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.*;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.support.MutableClock;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

@Testcontainers
class DeliveryRedeliveryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant BASE = Instant.parse("2026-10-29T14:00:00.123456Z");
    static final MutableClock CLOCK = new MutableClock(BASE);
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static long actor;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock redeliveryClock() { return CLOCK; }
    }
    @BeforeAll static void start() {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class, TimeConfiguration.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=redelivery-admin", "--HOOKFLOW_ADMIN_PASSWORD=redelivery-password",
                "--HOOKFLOW_MAX_ATTEMPTS=2", "--HOOKFLOW_RETRY_DELAYS=3125ms,7500ms",
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
        jdbc = context.getBean(JdbcTemplate.class);
        actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='redelivery-admin'", Long.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void resetClock() { CLOCK.setInstant(BASE); }
    @AfterEach void clear() {
        jdbc.update("DELETE FROM delivery_attempt"); jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM audit_log"); jdbc.update("DELETE FROM endpoint");
    }
    static String id() { return UUID.randomUUID().toString(); }
    static long endpoint() {
        return context.getBean(EndpointSecretProvisioningService.class).createExternal("redelivery",
                "https://hooks.example.com/old", 5000, true, actor, id()).configuration().id();
    }
    static Fixture pending(long ep) {
        String external = "order-redeliver-" + id();
        context.getBean(EventCreationService.class).create(external, "order.paid", ep,
                new EventPayloadCanonicalizer().canonicalize(JsonNodeFactory.instance.objectNode().put("value", 1))).orElseThrow();
        long pk = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, external);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?", Long.class, pk);
        return new Fixture(ep, pk, task, external);
    }
    static Fixture failed(long ep) {
        Fixture f = pending(ep);
        var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
        assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(),
                SingleDeliveryHttpResult.fromHttpResponse(404, "controlled-failure")));
        return f;
    }
    static DeliveryRedeliveryService.TaskSummary redeliver(Fixture f) {
        return context.getBean(DeliveryRedeliveryService.class).redeliver(f.external, actor, id());
    }
    static Map<String,Object> event(Fixture f) { return jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.event); }
    static Map<String,Object> task(long task) { return jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task); }
    static List<Map<String,Object>> attempts(long task) { return jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY id", task); }
    static long audits() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED'", Long.class); }
    static long rounds(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk=?", Long.class, f.event); }
    static LocalDateTime now() { return LocalDateTime.ofInstant(CLOCK.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); }
    static void same(Map<String,Object> expected, Map<String,Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String column : expected.keySet()) {
            if (expected.get(column) instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) actual.get(column), column);
            else assertEquals(expected.get(column), actual.get(column), column);
        }
    }
    static void sameAttempts(List<Map<String,Object>> before, long task) {
        var after = attempts(task); assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) same(before.get(i), after.get(i));
    }
    static void pendingRound(Fixture f, long task) {
        var row = task(task); assertEquals(f.event, ((Number) row.get("event_pk")).longValue());
        assertEquals(2, row.get("round_no")); assertEquals("PENDING", row.get("status")); assertEquals(0, row.get("attempt_count"));
        for (String column : List.of("resume_status", "resume_due_at", "claim_token", "lease_until", "finished_at")) assertNull(row.get(column), column);
        for (String column : List.of("created_at", "updated_at", "next_run_at"))
            assertEquals(now(), jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id=?", (rs, n) -> rs.getObject(1, LocalDateTime.class), task));
        assertEquals(2, event(f).get("latest_round_no")); assertEquals("PENDING", event(f).get("current_status")); assertNull(event(f).get("terminal_at"));
        assertTrue(attempts(task).isEmpty());
    }

    @Test void createsFreshRoundWithCurrentConfigurationAndPolicyPreservingAllOriginalData() {
        long ep = endpoint(); Fixture f = pending(ep);
        // 历史策略夹具与当前应用配置不同；旧轮仍经正式领取/完成产生全部失败尝试。
        jdbc.update("UPDATE delivery_task SET max_attempts=3,retry_delay_1_s=10,retry_delay_2_s=30 WHERE id=?", f.task);
        for (int i = 1; i <= 3; i++) {
            var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task, i, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(500, "failure")));
            if (i < 3) CLOCK.advance(Duration.ofSeconds(i == 1 ? 10 : 30));
        }
        var originalEvent = event(f); var originalTask = task(f.task); var originalAttempts = attempts(f.task);
        Fixture sameEndpoint = failed(ep), foreign = failed(endpoint());
        var sibling = event(sameEndpoint); var siblingTask = task(sameEndpoint.task); var foreignEvent = event(foreign); var foreignTask = task(foreign.task);
        context.getBean(EndpointUpdateService.class).update(ep, null, "https://hooks.example.com/new", 1234, actor, id()).orElseThrow();
        // 密钥不可通过正式编辑轮换；仅构造历史密文与当前密文不同的数据，验证重投按当前行复制且不解密。
        byte[] currentCiphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs, n) -> rs.getBytes(1), foreign.endpoint);
        jdbc.update("UPDATE endpoint SET signing_secret_ciphertext=? WHERE id=?", currentCiphertext, ep);
        CLOCK.advance(Duration.ofMillis(7)); var result = redeliver(f);
        pendingRound(f, result.taskId()); assertEquals(2, result.roundNo()); assertNull(result.lastAttemptAt());
        assertEquals(now().toInstant(ZoneOffset.UTC), result.createdAt()); assertEquals(f.external, result.eventId());
        var fresh = task(result.taskId()); assertEquals(2, fresh.get("max_attempts"));
        assertEquals(0, new BigDecimal("3.125").compareTo((BigDecimal) fresh.get("retry_delay_1_s")));
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) fresh.get("retry_delay_2_s")));
        assertEquals("EXTERNAL", fresh.get("target_type_snapshot")); assertEquals("https://hooks.example.com/new", fresh.get("url_snapshot")); assertEquals(1234, fresh.get("timeout_ms_snapshot"));
        assertArrayEquals(currentCiphertext, (byte[]) fresh.get("secret_ciphertext_snapshot"));
        same(originalTask, task(f.task)); sameAttempts(originalAttempts, f.task);
        for (String column : List.of("id", "event_id", "event_type", "endpoint_id", "payload_json", "created_at")) assertEquals(originalEvent.get(column), event(f).get(column), column);
        assertArrayEquals((byte[]) originalEvent.get("payload_fingerprint"), (byte[]) event(f).get("payload_fingerprint"));
        same(sibling, event(sameEndpoint)); same(siblingTask, task(sameEndpoint.task)); same(foreignEvent, event(foreign)); same(foreignTask, task(foreign.task));
        assertEquals(2, rounds(f)); assertEquals(1, audits()); assertEquals(3, originalAttempts.size());
        assertTrue(context.getBean(DeliveryClaimService.class).claim(result.taskId()).isPresent());
    }

    @Test void rejectsEveryNonFinalLatestStatusAndHistoricalFailureDoesNotPermitAnotherRound() {
        for (String status : List.of("PENDING", "WAIT_RETRY", "PAUSED", "DELIVERING", "SUCCESS")) {
            long ep = endpoint(); Fixture f = pending(ep);
            if (status.equals("PAUSED")) context.getBean(EndpointDisableService.class).disable(ep, actor, id()).orElseThrow();
            else if (!status.equals("PENDING")) {
                var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
                if (!status.equals("DELIVERING")) assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(status.equals("SUCCESS") ? 200 : 500, "result")));
            }
            // PAUSED+enabled 是明确标注的不一致启停历史夹具，仅为独立验证状态拒绝。
            if (status.equals("PAUSED")) jdbc.update("UPDATE endpoint SET enabled=1 WHERE id=?", ep);
            var beforeEvent = event(f); var beforeTask = task(f.task); var beforeAttempts = attempts(f.task);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> redeliver(f)).getStatusCode());
            same(beforeEvent, event(f)); same(beforeTask, task(f.task)); sameAttempts(beforeAttempts, f.task); assertEquals(1, rounds(f));
        }
        Fixture historical = failed(endpoint()); redeliver(historical);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> redeliver(historical)).getStatusCode());
        assertEquals(2, rounds(historical)); assertEquals(1, audits());
    }

    @Test void disabledEndpointHasExistingSnapshotErrorPriorityAndUnknownEventIs404() {
        long ep = endpoint(); Fixture f = failed(ep); context.getBean(EndpointDisableService.class).disable(ep, actor, id()).orElseThrow();
        var before = event(f); var old = task(f.task);
        assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(f));
        same(before, event(f)); same(old, task(f.task)); assertEquals(1, rounds(f)); assertEquals(0, audits());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> context.getBean(DeliveryRedeliveryService.class).redeliver("missing", actor, id())).getStatusCode());
        Fixture pending = pending(endpoint()); context.getBean(EndpointDisableService.class).disable(pending.endpoint, actor, id());
        assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(pending));
    }

    @Test void mismatchedEventAndLatestTaskCannotSilentlySucceed() {
        for (boolean eventFailed : List.of(true, false)) {
            Fixture f = failed(endpoint());
            // 真实表中的异常冗余状态夹具，不能当作正式状态转换。
            if (eventFailed) jdbc.update("UPDATE delivery_task SET status='SUCCESS' WHERE id=?", f.task);
            else jdbc.update("UPDATE event SET current_status='SUCCESS' WHERE id=?", f.event);
            var before = event(f); var old = task(f.task);
            assertThrows(IllegalStateException.class, () -> redeliver(f)); same(before, event(f)); same(old, task(f.task)); assertEquals(1, rounds(f));
        }
        assertEquals(0, audits());
    }

    @Test void realTaskInsertFailureRollsBack() { rollback("delivery_task", "round_no <> 2"); }
    @Test void realEventSyncFailureRollsBackTaskInsert() { rollback("event", "latest_round_no <> 2"); }
    @Test void realAuditFailureRollsBackTaskAndEvent() { rollback("audit_log", "action <> 'EVENT_REDELIVERED'"); }
    void rollback(String table, String expression) {
        Fixture f = failed(endpoint()); var before = event(f); var old = task(f.task); var history = attempts(f.task);
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT ck_redelivery_failure CHECK (" + expression + ")");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> redeliver(f));
            same(before, event(f)); same(old, task(f.task)); sameAttempts(history, f.task); assertEquals(1, rounds(f)); assertEquals(0, audits());
        } finally { jdbc.execute("ALTER TABLE " + table + " DROP CHECK ck_redelivery_failure"); }
        var result = redeliver(f); pendingRound(f, result.taskId()); assertEquals(1, audits());
    }

    @Test void callerRollbackLeavesNoNewRoundEventChangeOrAudit() {
        Fixture f = failed(endpoint()); var before = event(f); var old = task(f.task); var history = attempts(f.task);
        transaction().executeWithoutResult(tx -> { redeliver(f); tx.setRollbackOnly(); });
        same(before, event(f)); same(old, task(f.task)); sameAttempts(history, f.task);
        assertEquals(1, rounds(f)); assertEquals(0, audits());
        var result = redeliver(f); pendingRound(f, result.taskId());
    }

    @Test void duplicateRoundUniqueConstraintRemainsAnAtomicBackstop() {
        Fixture f = failed(endpoint());
        // 孤立下一轮是异常历史夹具；应用不得覆盖数据库唯一约束保护的行。
        jdbc.update("INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,retry_delay_1_s,retry_delay_2_s,next_run_at,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at) SELECT event_pk,2,'PENDING',0,max_attempts,retry_delay_1_s,retry_delay_2_s,?,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,?,? FROM delivery_task WHERE id=?", now(), now(), now(), f.task);
        var before = event(f);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> redeliver(f)); same(before, event(f)); assertEquals(2, rounds(f)); assertEquals(0, audits());
    }

    @Test void doubleClickReallyWaitsForWinningTransactionAndOnlyOneRoundAndAuditCommit() throws Exception {
        Fixture f = failed(endpoint()); var old = task(f.task); var history = attempts(f.task); var original = event(f);
        Object loser = ordered(f.endpoint, () -> redeliver(f), () -> {
            return assertThrows(ResponseStatusException.class, () -> redeliver(f)).getStatusCode();
        }, () -> {
            same(original, event(f)); assertEquals(1, rounds(f)); assertEquals(0, audits());
            context.getBean(DueDeliveryScanner.class).scanDueTasks(); sameAttempts(history, f.task);
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id<>?", Long.class, f.task));
        });
        assertEquals(HttpStatus.CONFLICT, loser); assertEquals(2, rounds(f)); assertEquals(1, audits());
        long fresh = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=? AND round_no=2", Long.class, f.event);
        pendingRound(f, fresh); same(old, task(f.task)); sameAttempts(history, f.task);
    }

    @Test void editingCommitsFirstAndNewRoundUsesNewSnapshot() throws Exception { editOrder(true); }
    @Test void redeliveryLocksFirstAndNewRoundUsesPreviousSnapshot() throws Exception { editOrder(false); }
    void editOrder(boolean editFirst) throws Exception {
        Fixture f = failed(endpoint()); var old = task(f.task); var history = attempts(f.task);
        Supplier<Object> edit = () -> context.getBean(EndpointUpdateService.class).update(f.endpoint, null, "https://hooks.example.com/new", 1234, actor, id()).orElseThrow();
        Supplier<Object> replay = () -> redeliver(f);
        ordered(f.endpoint, editFirst ? edit : replay, editFirst ? replay : edit, () -> { assertEquals(1, rounds(f)); assertEquals(0, audits()); });
        long fresh = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=? AND round_no=2", Long.class, f.event);
        assertEquals(editFirst ? "https://hooks.example.com/new" : "https://hooks.example.com/old", task(fresh).get("url_snapshot"));
        assertEquals(editFirst ? 1234 : 5000, task(fresh).get("timeout_ms_snapshot"));
        assertArrayEquals((byte[]) old.get("secret_ciphertext_snapshot"), (byte[]) task(fresh).get("secret_ciphertext_snapshot"));
        same(old, task(f.task)); sameAttempts(history, f.task); pendingRound(f, fresh); assertEquals(1, audits());
    }

    @Test void disablingFirstRejectsWaitingRedeliveryWithoutCreatingRound() throws Exception {
        Fixture f = failed(endpoint()); var original = event(f); var old = task(f.task); var history = attempts(f.task);
        ordered(f.endpoint, () -> context.getBean(EndpointDisableService.class).disable(f.endpoint, actor, id()).orElseThrow(),
                () -> assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(f)),
                () -> { assertEquals(1, rounds(f)); assertEquals(0, audits()); });
        same(original, event(f)); same(old, task(f.task)); sameAttempts(history, f.task); assertEquals(1, rounds(f)); assertEquals(0, audits());
    }

    @Test void redeliveryFirstMakesWaitingDisableSeeAndPausePreviouslyUncommittedNewRound() throws Exception {
        Fixture f = failed(endpoint()); var old = task(f.task); var history = attempts(f.task);
        ordered(f.endpoint, () -> redeliver(f), () -> context.getBean(EndpointDisableService.class).disable(f.endpoint, actor, id()).orElseThrow(),
                () -> { assertEquals(1, rounds(f)); assertEquals("FINAL_FAILED", event(f).get("current_status")); assertEquals(0, audits()); });
        long fresh = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=? AND round_no=2", Long.class, f.event);
        var row = task(fresh); assertEquals("PAUSED", row.get("status")); assertEquals("PENDING", row.get("resume_status")); assertNull(row.get("next_run_at"));
        assertEquals("PAUSED", event(f).get("current_status")); assertEquals(2, event(f).get("latest_round_no")); assertEquals(0, row.get("attempt_count"));
        assertTrue(attempts(fresh).isEmpty()); assertEquals(1, audits()); same(old, task(f.task)); sameAttempts(history, f.task);
    }

    @Test void oldTokenAndOldRoundCompletionCannotOverwriteNewLatestEvent() {
        Fixture f = pending(endpoint()); var claim = context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
        assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(404, "failed")));
        var history = attempts(f.task); var old = task(f.task); var fresh = redeliver(f); var event = event(f);
        assertFalse(context.getBean(DeliveryCompletionService.class).complete(f.task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(200, "late")));
        assertFalse(context.getBean(DeliveryRecoveryService.class).recover(f.task, claim.claimToken()));
        same(event, event(f)); same(old, task(f.task)); sameAttempts(history, f.task); pendingRound(f, fresh.taskId());
    }

    Object ordered(long endpoint, Supplier<?> winner, Supplier<?> loser, Runnable whileWaiting) throws Exception {
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        return new com.hookflow.support.MySqlRowLockRace(jdbc, observer, DeliveryRedeliveryIntegrationTests::transaction)
                .ordered(endpoint, winner, loser, whileWaiting);
    }
    static TransactionTemplate transaction() {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx;
    }
    record Fixture(long endpoint, long event, long task, String external) { }
}
