package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.CanonicalEventPayload;
import com.hookflow.support.MutableClock;
import java.time.*;
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
class EndpointDisableIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant BASE = Instant.parse("2026-10-29T13:00:00.123456Z");
    static final LocalDateTime NOW = LocalDateTime.ofInstant(BASE.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZoneOffset.UTC);
    static final LocalDateTime DUE = NOW.minusHours(1);
    static final MutableClock CLOCK = new MutableClock(BASE);
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static long actor;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock disableClock() { return CLOCK; }
    }

    @BeforeAll static void start() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class, TimeConfiguration.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(), "--HOOKFLOW_ADMIN_USERNAME=disable-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=disable-password",
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
        jdbc = context.getBean(JdbcTemplate.class);
        actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'disable-admin'", Long.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void resetClock() { CLOCK.setInstant(BASE); }
    @AfterEach void clear() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM endpoint");
    }

    long endpoint() {
        return context.getBean(EndpointSecretProvisioningService.class).createExternal("disable",
                "https://hooks.example.com/old", 5000, true, actor, requestId()).configuration().id();
    }
    Fixture fixture(long endpoint, String state, LocalDateTime due) {
        String id = UUID.randomUUID().toString();
        boolean terminal = Set.of("SUCCESS", "FINAL_FAILED").contains(state);
        jdbc.update("INSERT INTO event (event_id,event_type,payload_json,payload_fingerprint,endpoint_id,"
                        + "latest_round_no,current_status,created_at,terminal_at) VALUES "
                        + "(?,'order.paid','null',UNHEX(SHA2('null',256)),?,1,?,?,?)",
                id, endpoint, state, DUE, terminal ? DUE : null);
        long event = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, id);
        int count = "PENDING".equals(state) ? 0 : 1;
        jdbc.update("INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,"
                        + "retry_delay_1_s,retry_delay_2_s,next_run_at,resume_status,resume_due_at,lease_until,claim_token,"
                        + "target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at,finished_at) "
                        + "SELECT ?,1,?,?,3,7.125,19.5,?,?,?,?,?,'EXTERNAL',url,timeout_ms,signing_secret_ciphertext,?,?,? "
                        + "FROM endpoint WHERE id = ?",
                event, state, count, Set.of("PENDING", "WAIT_RETRY").contains(state) ? due : null,
                "PAUSED".equals(state) ? "WAIT_RETRY" : null, "PAUSED".equals(state) ? due : null,
                "DELIVERING".equals(state) ? NOW.plusMinutes(1) : null,
                "DELIVERING".equals(state) ? requestId() : null, DUE, DUE, terminal ? DUE : null, endpoint);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, event);
        if (count > 0) {
            boolean started = "DELIVERING".equals(state);
            jdbc.update("INSERT INTO delivery_attempt (task_id,attempt_no,result,started_at,ended_at,duration_ms,request_id) "
                            + "VALUES (?,1,?,?,?,?,?)", task, started ? "STARTED" : "HTTP_FAILURE", DUE,
                    started ? null : DUE, started ? null : 0L, requestId());
        }
        return new Fixture(endpoint, event, task);
    }
    static String requestId() { return UUID.randomUUID().toString(); }
    static void disable(long id) { context.getBean(EndpointDisableService.class).disable(id, actor, requestId()).orElseThrow(); }
    static Map<String,Object> task(Fixture f) { return jdbc.queryForMap("SELECT * FROM delivery_task WHERE id = ?", f.task); }
    static String state(Fixture f) { return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, f.task); }
    static void consistent(Fixture f, String expected) {
        assertEquals(expected, state(f));
        assertEquals(expected, jdbc.queryForObject("SELECT current_status FROM event WHERE id = ?", String.class, f.event));
        assertEquals(1, jdbc.queryForObject("SELECT latest_round_no FROM event WHERE id = ?", Integer.class, f.event));
    }
    static long attempts(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?", Long.class, f.task); }
    static LocalDateTime time(Fixture f, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), f.task);
    }
    static void snapshotEquals(Map<String,Object> before, Map<String,Object> after) {
        for (String column : List.of("event_pk", "round_no", "attempt_count", "max_attempts", "retry_delay_1_s",
                "retry_delay_2_s", "target_type_snapshot", "url_snapshot", "timeout_ms_snapshot", "created_at")) {
            assertEquals(before.get(column), after.get(column), column);
        }
        assertArrayEquals((byte[])before.get("secret_ciphertext_snapshot"), (byte[])after.get("secret_ciphertext_snapshot"));
    }

    @Test void pausesOnlyUnsentLatestTasksAndPreservesEverySnapshotAndHistory() {
        long endpoint = endpoint();
        List<Fixture> pending = List.of(fixture(endpoint, "PENDING", DUE), fixture(endpoint, "PENDING", NOW.plusHours(2)),
                fixture(endpoint, "WAIT_RETRY", DUE), fixture(endpoint, "WAIT_RETRY", NOW.plusHours(3)));
        List<Map<String,Object>> before = pending.stream().map(EndpointDisableIntegrationTests::task).toList();
        List<Fixture> unchanged = List.of(fixture(endpoint,"PAUSED", DUE.minusDays(1)),
                fixture(endpoint,"DELIVERING", null), fixture(endpoint,"SUCCESS", null), fixture(endpoint,"FINAL_FAILED", null));
        List<Map<String,Object>> unchangedBefore = unchanged.stream().map(EndpointDisableIntegrationTests::task).toList();
        Fixture history = fixture(endpoint,"WAIT_RETRY", DUE);
        Map<String,Object> oldRound = task(history);
        jdbc.update("INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,retry_delay_1_s,"
                        + "retry_delay_2_s,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,"
                        + "created_at,updated_at,finished_at) SELECT event_pk,2,'SUCCESS',1,max_attempts,retry_delay_1_s,"
                        + "retry_delay_2_s,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,"
                        + "created_at,updated_at,? FROM delivery_task WHERE id = ?", NOW, history.task);
        jdbc.update("UPDATE event SET latest_round_no=2,current_status='SUCCESS',terminal_at=? WHERE id=?", NOW, history.event);
        long attemptCount = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class);
        disable(endpoint);
        for (int i=0; i<pending.size(); i++) {
            Fixture f = pending.get(i);
            consistent(f,"PAUSED");
            assertEquals(before.get(i).get("status"), task(f).get("resume_status"));
            assertEquals(before.get(i).get("next_run_at"), task(f).get("resume_due_at"));
            assertNull(time(f,"next_run_at"));
            assertEquals(NOW, time(f,"updated_at"));
            snapshotEquals(before.get(i), task(f));
        }
        for (int i=0; i<unchanged.size(); i++) assertTaskEquals(unchangedBefore.get(i),task(unchanged.get(i)));
        assertTaskEquals(oldRound,task(history));
        assertEquals("SUCCESS",jdbc.queryForObject("SELECT current_status FROM event WHERE id=?",String.class,history.event));
        assertEquals(attemptCount,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt",Long.class));
        List<Map<String,Object>> paused = pending.stream().map(EndpointDisableIntegrationTests::task).toList();
        CLOCK.advance(Duration.ofHours(1));
        disable(endpoint);
        for (int i=0;i<pending.size();i++) assertTaskEquals(paused.get(i),task(pending.get(i)));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_DISABLED'",Long.class));
        assertEquals(NOW,jdbc.queryForObject("SELECT updated_at FROM endpoint WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),endpoint));
    }
    static void assertTaskEquals(Map<String,Object> before, Map<String,Object> after) {
        snapshotEquals(before,after);
        for (String key : before.keySet()) if (!(before.get(key) instanceof byte[])) assertEquals(before.get(key),after.get(key),key);
    }

    @Test void taskFailureRollsBackEarlierPausedTasksEndpointAndAudit() { rollback("delivery_task", "retry_delay_1_s <> 8.125 OR status <> 'PAUSED'"); }
    @Test void eventFailureRollsBackTaskAndEndpointAndAudit() { rollback("event", "event_type <> 'rollback-reject' OR current_status <> 'PAUSED'"); }
    @Test void auditDatabaseFailureRollsBackAllTaskAndEventChanges() { rollback("audit_log", "object_id <> '%d' OR action <> 'ENDPOINT_DISABLED'"); }
    void rollback(String table, String expression) {
        long ep = endpoint();
        Fixture first = fixture(ep,"PENDING",DUE);
        Fixture second = fixture(ep,"WAIT_RETRY",DUE);
        if (table.equals("delivery_task")) jdbc.update("UPDATE delivery_task SET retry_delay_1_s=8.125 WHERE id=?",second.task);
        if (table.equals("event")) jdbc.update("UPDATE event SET event_type='rollback-reject' WHERE id=?",second.event);
        long id = ep;
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT ck_disable_failure CHECK (" + expression.formatted(id) + ")");
        try {
            assertThrows(RuntimeException.class,()->disable(ep));
            assertEquals(1,jdbc.queryForObject("SELECT enabled FROM endpoint WHERE id=?",Integer.class,ep));
            consistent(first,"PENDING"); consistent(second,"WAIT_RETRY");
            assertNull(task(first).get("resume_status")); assertNull(task(second).get("resume_status"));
            assertEquals(DUE,time(first,"next_run_at")); assertEquals(DUE,time(second,"next_run_at"));
            assertEquals(DUE,time(first,"updated_at")); assertEquals(0,attempts(first)); assertEquals(1,attempts(second));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_DISABLED'",Long.class));
        } finally { jdbc.execute("ALTER TABLE " + table + " DROP CHECK ck_disable_failure"); }
        disable(ep); consistent(first,"PAUSED"); consistent(second,"PAUSED");
    }

    @Test void disableWinsRealEndpointLockAndClaimCreatesNoStarted() throws Exception {
        long ep=endpoint(); Fixture f=fixture(ep,"PENDING",DUE);
        Object result = ordered(ep,()->{ disable(ep); return true; },
                ()->context.getBean(DeliveryClaimService.class).claim(f.task));
        assertTrue(((Optional<?>)result).isEmpty()); consistent(f,"PAUSED"); assertEquals(0,attempts(f));
        assertEquals(0,task(f).get("attempt_count"));
    }
    @Test void claimWinsRealEndpointLockAndOnlyStartedAttemptCanFinishAfterDisable() throws Exception {
        long ep=endpoint(); Fixture f=fixture(ep,"PENDING",DUE); Fixture other=fixture(ep,"PENDING",DUE);
        var claim = new java.util.concurrent.atomic.AtomicReference<DeliveryClaimService.ClaimedTask>();
        ordered(ep,()->{ claim.set(context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow()); return true; },
                ()->{disable(ep);return true;});
        consistent(f,"DELIVERING"); consistent(other,"PAUSED"); assertEquals(1,attempts(f)); assertEquals(0,attempts(other));
        assertEquals(claim.get().claimToken(),task(f).get("claim_token"));
        assertEquals(claim.get().leaseUntil(),time(f,"lease_until"));
        assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task,1,claim.get().claimToken(),
                SingleDeliveryHttpResult.fromHttpResponse(200,"ok")));
        consistent(f,"SUCCESS"); assertEquals(1,attempts(f));
    }
    @Test void createCommitsFirstAndDisableSeesItsPreviouslyUncommittedFirstTask() throws Exception {
        long ep=endpoint(); String id=requestId();
        ordered(ep,()->context.getBean(EventCreationService.class).create(id,"order.paid",ep,payload()).orElseThrow(),
                ()->{disable(ep);return true;});
        long event=jdbc.queryForObject("SELECT id FROM event WHERE event_id=?",Long.class,id);
        Fixture f=new Fixture(ep,event,jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?",Long.class,event));
        consistent(f,"PAUSED"); assertEquals(0,attempts(f)); assertEquals("PENDING",task(f).get("resume_status"));
    }
    @Test void disableCommitsFirstAndWaitingCreationIsRejectedWithoutRows() throws Exception {
        long ep=endpoint();
        Object failure = ordered(ep,()->{disable(ep);return true;},()->{
            try {context.getBean(EventCreationService.class).create(requestId(),"order.paid",ep,payload());return null;}
            catch (RuntimeException exception) {return exception;}
        });
        assertInstanceOf(com.hookflow.endpoint.application.EndpointTaskSnapshotService.EndpointDisabledException.class,failure);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE endpoint_id=?",Long.class,ep));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task",Long.class));
    }
    @Test void disableAndRetryableCompletionSerializeInBothRealLockOrders() throws Exception {
        for(boolean disableFirst : List.of(true,false)) {
            long ep=endpoint(); Fixture f=fixture(ep,"PENDING",DUE);
            var claim=context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
            CLOCK.advance(Duration.ofSeconds(1));
            Supplier<Object> finish=()->context.getBean(DeliveryCompletionService.class).complete(f.task,1,claim.claimToken(),SingleDeliveryHttpResult.fromHttpResponse(500,"retry"));
            Supplier<Object> disable=()->{disable(ep);return true;};
            assertEquals(true,ordered(ep,disableFirst?disable:finish,disableFirst?finish:disable));
            consistent(f,"PAUSED"); assertEquals("WAIT_RETRY",task(f).get("resume_status"));
            assertEquals(LocalDateTime.ofInstant(CLOCK.instant(),ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusNanos(7_125_000_000L),time(f,"resume_due_at"));
            assertEquals("HTTP_FAILURE",jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?",String.class,f.task));
            assertEquals(1,attempts(f)); assertNull(task(f).get("claim_token")); assertNull(time(f,"lease_until"));
        }
    }
    @Test void nonRetryableOrExhaustedInFlightResultsRemainTerminalAfterActualDisable() {
        for(int status : List.of(404,500)) {
            long ep=endpoint();Fixture f=fixture(ep,"PENDING",DUE);
            if(status==500) jdbc.update("UPDATE delivery_task SET max_attempts=1 WHERE id=?",f.task);
            var claim=context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();
            disable(ep);
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(f.task,1,claim.claimToken(),SingleDeliveryHttpResult.fromHttpResponse(status,"failed")));
            consistent(f,"FINAL_FAILED");assertNull(task(f).get("resume_status"));assertNotNull(time(f,"finished_at"));assertEquals(1,attempts(f));
        }
    }
    @Test void disabledExpiredStartedStillRecoversUnknownAndRejectsLateResult() {
        long ep=endpoint();Fixture f=fixture(ep,"PENDING",DUE);
        var claim=context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow();disable(ep);
        CLOCK.setInstant(claim.leaseUntil().toInstant(ZoneOffset.UTC));
        assertTrue(context.getBean(DeliveryRecoveryService.class).recover(f.task,claim.claimToken()));
        consistent(f,"PAUSED");assertEquals("WAIT_RETRY",task(f).get("resume_status"));
        assertEquals(claim.leaseUntil().plusNanos(7_125_000_000L),time(f,"resume_due_at"));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?",String.class,f.task));
        assertFalse(context.getBean(DeliveryCompletionService.class).complete(f.task,1,claim.claimToken(),SingleDeliveryHttpResult.fromHttpResponse(200,"late")));
        consistent(f,"PAUSED"); assertEquals(1,attempts(f));
    }
    static CanonicalEventPayload payload() {
        return new com.hookflow.event.domain.EventPayloadCanonicalizer()
                .canonicalize(tools.jackson.databind.node.JsonNodeFactory.instance.nullNode());
    }

    Object ordered(long endpoint, Supplier<?> winner, Supplier<?> loser) throws Exception {
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        return new com.hookflow.support.MySqlRowLockRace(jdbc, observer, EndpointDisableIntegrationTests::transaction)
                .ordered(endpoint, winner, loser, () -> { });
    }
    static TransactionTemplate transaction() {
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);return tx;
    }
    record Fixture(long endpoint,long event,long task) { }
}
