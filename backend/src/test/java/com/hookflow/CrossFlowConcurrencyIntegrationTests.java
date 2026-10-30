package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.*;
import com.hookflow.delivery.application.DeliveryClaimService.ClaimedTask;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.*;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.support.MutableClock;
import com.hookflow.support.MySqlRowLockRace;
import java.time.*;
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
class CrossFlowConcurrencyIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant BASE = Instant.parse("2026-10-30T00:30:00.123Z");
    static final MutableClock CLOCK = new MutableClock(BASE);
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static MySqlRowLockRace race;
    static long actor;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock crossFlowClock() { return CLOCK; }
    }
    @BeforeAll static void start() {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class, TimeConfiguration.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=cross-flow-admin", "--HOOKFLOW_ADMIN_PASSWORD=cross-flow-password",
                "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=8",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.setQueryTimeout(15);
        actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='cross-flow-admin'", Long.class);
        race = new MySqlRowLockRace(jdbc, new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())), CrossFlowConcurrencyIntegrationTests::transaction);
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void resetClock() { CLOCK.setInstant(BASE); }
    @AfterEach void clear() {
        jdbc.update("DELETE FROM delivery_attempt"); jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM audit_log"); jdbc.update("DELETE FROM endpoint");
    }

    @Test void duplicateClaimReallyWaitsThenRechecksStartedAndAttemptLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            Fixture f = pending(endpoint()); var original = task(f); var event = event(f); var audits = audits();
            AtomicReference<ClaimedTask> won = new AtomicReference<>();
            assertTrue(((Optional<?>) race.ordered(f.endpoint, () -> { won.set(claim(f)); return true; },
                    () -> context.getBean(DeliveryClaimService.class).claim(f.task), () -> {
                        same(original, task(f)); same(event, event(f)); assertTrue(attempts(f).isEmpty());
                    })).isEmpty());
            consistent(f, "DELIVERING"); assertEquals(1, task(f).get("attempt_count"));
            assertEquals(won.get().claimToken(), task(f).get("claim_token"));
            assertEquals(won.get().leaseUntil(), time(f, "lease_until"));
            assertEquals(1, attempts(f).size()); assertEquals(1, attempts(f).getFirst().get("attempt_no"));
            assertEquals("STARTED", attempts(f).getFirst().get("result")); sameRows(audits, audits());
        }
    }

    @Test void oppositeEnableDisableOrdersAndConcurrentNoOpsPreserveRetryDue() throws Exception {
        for (boolean enableFirst : List.of(true, false)) {
            long ep = endpoint(); Fixture p = pending(ep), retry = retry(ep);
            LocalDateTime due = time(retry, "next_run_at"); var snapshot = task(retry);
            if (enableFirst) disable(ep);
            for (int repeat = 0; repeat < 3; repeat++) {
                var before = database(); CLOCK.advance(Duration.ofMillis(1));
                race.ordered(ep, () -> { if (enableFirst) enable(ep); else disable(ep); return true; },
                        () -> { if (enableFirst) disable(ep); else enable(ep); return true; }, () -> sameRows(before, database()));
                consistent(p, enableFirst ? "PAUSED" : "PENDING");
                consistent(retry, enableFirst ? "PAUSED" : "WAIT_RETRY");
                assertEquals(enableFirst ? 0 : 1, jdbc.queryForObject("SELECT enabled FROM endpoint WHERE id=?", Integer.class, ep));
                assertEquals(due, time(retry, enableFirst ? "resume_due_at" : "next_run_at")); snapshot(snapshot, task(retry));
                assertEquals(1, attempts(retry).size()); assertTrue(attempts(p).isEmpty());
                var unchanged = database();
                race.ordered(ep, () -> { if (enableFirst) disable(ep); else enable(ep); return true; },
                        () -> { if (enableFirst) disable(ep); else enable(ep); return true; }, () -> sameRows(unchanged, database()));
                sameRows(unchanged, database());
                if (enableFirst) disable(ep); else enable(ep);
            }
        }
    }

    @Test void recoveryAndEnableSerializeBothOrdersIncludingExhaustion() throws Exception { recoveryToggle(true); }
    @Test void recoveryAndDisableSerializeBothOrdersIncludingExhaustion() throws Exception { recoveryToggle(false); }
    void recoveryToggle(boolean enabling) throws Exception {
        for (boolean toggleFirst : List.of(true, false)) for (boolean exhausted : List.of(true, false)) {
            long ep = endpoint(); Fixture f = pending(ep); if (exhausted) oneAttemptPolicy(f);
            ClaimedTask c = claim(f); Fixture sibling = pending(ep);
            if (enabling) disable(ep);
            CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC)); LocalDateTime due = c.leaseUntil().plusSeconds(10);
            var before = database();
            Supplier<Object> toggle = () -> { if (enabling) enable(ep); else disable(ep); return true; };
            Supplier<Object> recovery = () -> recover(f, c);
            assertEquals(true, race.ordered(ep, toggleFirst ? toggle : recovery, toggleFirst ? recovery : toggle,
                    () -> sameRows(before, database())));
            String expected = exhausted ? "FINAL_FAILED" : enabling ? "WAIT_RETRY" : "PAUSED";
            consistent(f, expected); consistent(sibling, enabling ? "PENDING" : "PAUSED");
            assertEquals(1, task(f).get("attempt_count")); assertTrue(attempts(sibling).isEmpty());
            interrupted(f); assertNull(task(f).get("claim_token")); assertNull(time(f, "lease_until"));
            if (!exhausted) assertEquals(due, time(f, enabling ? "next_run_at" : "resume_due_at"));
            else assertEquals(c.leaseUntil(), time(f, "finished_at"));
            var finalRows = database(); assertFalse(recover(f, c)); assertFalse(complete(f, c, 200)); sameRows(finalRows, database());
        }
    }

    @Test void terminalCompletionsAndEnableDisablePreserveAttemptsInBothOrders() throws Exception {
        for (boolean enabling : List.of(true, false)) for (boolean toggleFirst : List.of(true, false))
            for (int status : List.of(200, 404, 500)) {
                long ep = endpoint(); Fixture f = pending(ep); if (status == 500) oneAttemptPolicy(f);
                ClaimedTask c = claim(f); if (enabling) disable(ep);
                var before = database();
                Supplier<Object> toggle = () -> { if (enabling) enable(ep); else disable(ep); return true; };
                Supplier<Object> finish = () -> complete(f, c, status);
                assertEquals(true, race.ordered(ep, toggleFirst ? toggle : finish, toggleFirst ? finish : toggle,
                        () -> sameRows(before, database())));
                consistent(f, status == 200 ? "SUCCESS" : "FINAL_FAILED");
                assertEquals(status == 200 ? "SUCCESS" : "HTTP_FAILURE", attempts(f).getFirst().get("result"));
                assertEquals(status, attempts(f).getFirst().get("http_status")); assertEquals(1, attempts(f).size());
                assertNull(task(f).get("claim_token")); assertNull(time(f, "lease_until")); assertNull(task(f).get("resume_status"));
                assertEquals(time(f, "finished_at"), event(f).get("terminal_at"));
                var finalRows = database(); assertFalse(complete(f, c, 500)); sameRows(finalRows, database());
            }
    }

    @Test void completionAndRecoveryActuallyCompeteAtComplementaryLeaseBoundary() throws Exception {
        for (boolean completionFirst : List.of(true, false)) for (boolean expired : List.of(true, false)) {
            Fixture f = pending(endpoint()); ClaimedTask c = claim(f);
            CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC).minusMillis(expired ? 0 : 1));
            var original = database();
            Supplier<Object> finish = () -> { assertEquals(!expired, complete(f, c, 200)); return !expired; };
            Supplier<Object> recovery = () -> { assertEquals(expired, recover(f, c)); return expired; };
            race.ordered(f.endpoint, completionFirst ? finish : recovery, completionFirst ? recovery : finish,
                    () -> sameRows(original, database()));
            consistent(f, expired ? "WAIT_RETRY" : "SUCCESS");
            if (expired) { interrupted(f); assertEquals(c.leaseUntil().plusSeconds(10), time(f, "next_run_at")); }
            else assertEquals("SUCCESS", attempts(f).getFirst().get("result"));
            var after = database(); assertFalse(recover(f, c)); assertFalse(complete(f, c, 200)); sameRows(after, database());
        }
    }

    @Test void completionAndRecoveryHoldEndpointEventTaskBeforeWaitingForStartedAttempt() throws Exception {
        for (boolean recovering : List.of(true, false)) {
            Fixture f = pending(endpoint()); ClaimedTask c = claim(f);
            if (recovering) CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC));
            long attempt = ((Number) attempts(f).getFirst().get("id")).longValue(); var original = database();
            var workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(2));
            CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Long> blocker = new AtomicReference<>(), writer = new AtomicReference<>();
            var probe = workers.submit(() -> hold("delivery_attempt", attempt, blocker, held, release));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var writing = workers.submit(() -> transaction().execute(tx -> {
                    writer.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    return recovering ? recover(f, c) : complete(f, c, 200);
                }));
                race.awaitWait(writer, blocker, "delivery_attempt", attempt, writing);
                assertTrue(race.holds(writer.get(), "endpoint", f.endpoint)); assertTrue(race.holds(writer.get(), "event", f.event));
                assertTrue(race.holds(writer.get(), "delivery_task", f.task)); sameRows(original, database());
                release.countDown(); probe.get(5, TimeUnit.SECONDS); assertEquals(true, writing.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
            consistent(f, recovering ? "WAIT_RETRY" : "SUCCESS");
            if (recovering) interrupted(f); else assertEquals("SUCCESS", attempts(f).getFirst().get("result"));
        }
    }

    @Test void duplicateRecoveryAndDuplicateCompletionHaveOnlyOneWritingWinner() throws Exception {
        for (boolean recovery : List.of(true, false)) {
            Fixture f = pending(endpoint()); ClaimedTask c = claim(f);
            if (recovery) CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC));
            var original = database();
            assertEquals(false, race.ordered(f.endpoint,
                    () -> { assertTrue(recovery ? recover(f, c) : complete(f, c, 500)); return true; },
                    () -> recovery ? recover(f, c) : complete(f, c, 200), () -> sameRows(original, database())));
            consistent(f, "WAIT_RETRY"); assertEquals(1, task(f).get("attempt_count")); assertEquals(1, attempts(f).size());
            if (recovery) interrupted(f); else assertEquals("HTTP_FAILURE", attempts(f).getFirst().get("result"));
        }
    }

    @Test void finalizationAndRedeliveryUsePostLockLatestStateAndPreserveOldHistory() throws Exception {
        for (boolean recovery : List.of(true, false)) for (boolean finalizationFirst : List.of(true, false)) {
            Fixture f = pending(endpoint()); oneAttemptPolicy(f); ClaimedTask c = claim(f);
            if (recovery) CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC));
            var before = database(); AtomicReference<Map<String,Object>> terminal = new AtomicReference<>();
            AtomicReference<List<Map<String,Object>>> history = new AtomicReference<>();
            Supplier<Object> finalize = () -> {
                assertTrue(recovery ? recover(f, c) : complete(f, c, 404));
                terminal.set(task(f)); history.set(attempts(f)); return true;
            };
            Supplier<Object> replay = () -> finalizationFirst ? redeliver(f)
                    : assertThrows(ResponseStatusException.class, () -> redeliver(f)).getStatusCode();
            Object result = race.ordered(f.endpoint, finalizationFirst ? finalize : replay,
                    finalizationFirst ? replay : finalize, () -> sameRows(before, database()));
            if (finalizationFirst) {
                assertInstanceOf(DeliveryRedeliveryService.TaskSummary.class, result); consistent(f, "PENDING");
                assertEquals(2, event(f).get("latest_round_no"));
            } else { consistent(f, "FINAL_FAILED"); assertEquals(1, event(f).get("latest_round_no")); }
            same(terminal.get(), task(f)); sameRows(history.get(), attempts(f));
            assertEquals(finalizationFirst ? 1L : 0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED' AND object_id=?", Long.class, Long.toString(f.event)));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE event_id=?", Long.class, f.external));
            var after = database(); assertFalse(complete(f, c, 200)); assertFalse(recover(f, c)); sameRows(after, database());
        }
    }

    @Test void enableAndRedeliveryRecheckEnabledStateInBothOrders() throws Exception {
        for (boolean enableFirst : List.of(true, false)) {
            Fixture f = pending(endpoint()); ClaimedTask c = claim(f); assertTrue(complete(f, c, 404)); disable(f.endpoint);
            var old = task(f); var history = attempts(f); var before = database();
            Supplier<Object> replay = () -> enableFirst ? redeliver(f)
                    : assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(f));
            Supplier<Object> enabling = () -> { enable(f.endpoint); return true; };
            race.ordered(f.endpoint, enableFirst ? enabling : replay, enableFirst ? replay : enabling, () -> sameRows(before, database()));
            consistent(f, enableFirst ? "PENDING" : "FINAL_FAILED");
            assertEquals(enableFirst ? 2 : 1, event(f).get("latest_round_no")); same(old, task(f)); sameRows(history, attempts(f));
            assertEquals(1, jdbc.queryForObject("SELECT enabled FROM endpoint WHERE id=?", Integer.class, f.endpoint));
            assertEquals(enableFirst ? 1L : 0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED' AND object_id=?", Long.class, Long.toString(f.event)));
        }
    }

    @Test void claimEventDatabaseFailureRollsBackTaskAndInsertedStarted() { eventFailure("claim"); }
    @Test void completionEventDatabaseFailureRollsBackAlreadyFinishedAttemptAndTask() { eventFailure("complete"); }
    @Test void recoveryEventDatabaseFailureRollsBackAlreadyInterruptedAttemptAndTask() { eventFailure("recover"); }
    void eventFailure(String operation) {
        Fixture f = pending(endpoint()); ClaimedTask c = operation.equals("claim") ? null : claim(f);
        if (operation.equals("recover")) CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC));
        var before = database();
        String rejected = operation.equals("claim") ? "DELIVERING" : operation.equals("complete") ? "SUCCESS" : "WAIT_RETRY";
        jdbc.execute("ALTER TABLE event ADD CONSTRAINT ck_cross_flow_event_failure CHECK (current_status <> '" + rejected + "')");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> {
                switch (operation) { case "claim" -> claim(f); case "complete" -> complete(f, c, 200); case "recover" -> recover(f, c); default -> throw new IllegalStateException(); }
            });
            sameRows(before, database()); consistent(f, operation.equals("claim") ? "PENDING" : "DELIVERING");
        } finally { jdbc.execute("ALTER TABLE event DROP CHECK ck_cross_flow_event_failure"); }
        switch (operation) { case "claim" -> claim(f); case "complete" -> assertTrue(complete(f, c, 200)); case "recover" -> assertTrue(recover(f, c)); default -> throw new IllegalStateException(); }
    }

    @Test void mixedBatchDisableCannotMissCommittedClaimCompletionRecoveryCreationOrNewRound() throws Exception {
        for (String operation : List.of("claim", "complete", "recover", "create", "redeliver"))
            for (boolean disableFirst : List.of(true, false)) {
                long ep = endpoint(); Fixture pending = pending(ep), retry = retry(ep), focus = pending(ep);
                var retrySnapshot = task(retry); LocalDateTime retryDue = time(retry, "next_run_at");
                ClaimedTask c = Set.of("complete", "recover", "redeliver").contains(operation) ? claim(focus) : null;
                if (operation.equals("redeliver")) assertTrue(complete(focus, c, 404));
                if (operation.equals("recover")) CLOCK.setInstant(c.leaseUntil().toInstant(ZoneOffset.UTC));
                Fixture foreign = pending(endpoint()); var foreignTask = task(foreign); var foreignEvent = event(foreign);
                var old = task(focus); var history = attempts(focus); var before = database();
                AtomicReference<Fixture> created = new AtomicReference<>(); AtomicReference<Long> newRound = new AtomicReference<>();
                Supplier<Object> mutate = () -> {
                    return switch (operation) {
                        case "claim" -> { var result = context.getBean(DeliveryClaimService.class).claim(focus.task); assertEquals(!disableFirst, result.isPresent()); yield true; }
                        case "complete" -> { assertTrue(complete(focus, c, 500)); yield true; }
                        case "recover" -> { assertTrue(recover(focus, c)); yield true; }
                        case "create" -> {
                            if (disableFirst) yield assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> pending(ep));
                            created.set(pending(ep)); yield true;
                        }
                        case "redeliver" -> {
                            if (disableFirst) yield assertThrows(EndpointTaskSnapshotService.EndpointDisabledException.class, () -> redeliver(focus));
                            newRound.set(redeliver(focus).taskId()); yield true;
                        }
                        default -> throw new IllegalStateException();
                    };
                };
                Supplier<Object> stop = () -> { disable(ep); return true; };
                race.ordered(ep, disableFirst ? stop : mutate, disableFirst ? mutate : stop, () -> sameRows(before, database()));
                consistent(pending, "PAUSED"); consistent(retry, "PAUSED"); snapshot(retrySnapshot, task(retry));
                assertEquals(retryDue, time(retry, "resume_due_at"));
                if (operation.equals("claim")) consistent(focus, disableFirst ? "PAUSED" : "DELIVERING");
                else if (operation.equals("redeliver")) {
                    consistent(focus, disableFirst ? "FINAL_FAILED" : "PAUSED"); same(old, task(focus)); sameRows(history, attempts(focus));
                    assertEquals(disableFirst ? 1 : 2, event(focus).get("latest_round_no"));
                    if (newRound.get() != null) assertEquals("PENDING", jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id=?", String.class, newRound.get()));
                } else if (operation.equals("complete") || operation.equals("recover")) {
                    consistent(focus, "PAUSED"); assertEquals("WAIT_RETRY", task(focus).get("resume_status"));
                    if (operation.equals("recover")) interrupted(focus);
                }
                if (created.get() != null) { consistent(created.get(), "PAUSED"); assertTrue(attempts(created.get()).isEmpty()); }
                assertTrue(attempts(pending).isEmpty()); same(foreignTask, task(foreign)); same(foreignEvent, event(foreign));
            }
    }

    @Test void batchPauseActuallyLocksEventsAscendingBeforeTheirTasks() throws Exception { batchLockOrder(false); }
    @Test void batchResumeActuallyLocksEventsAscendingBeforeTheirTasks() throws Exception { batchLockOrder(true); }
    void batchLockOrder(boolean enabling) throws Exception {
        long ep = endpoint(); Fixture first = pending(ep), second = pending(ep), third = retry(ep);
        ClaimedTask oldClaim = claim(first); assertTrue(complete(first, oldClaim, 404));
        var old = task(first); var oldHistory = attempts(first);
        long fresh = redeliver(first).taskId();
        assertTrue(fresh > second.task, "刻意让 event 顺序与 task 主键顺序相反");
        Fixture active = pending(ep); ClaimedTask activeClaim = claim(active);
        Fixture terminal = pending(ep); ClaimedTask terminalClaim = claim(terminal); assertTrue(complete(terminal, terminalClaim, 200));
        Fixture foreign = pending(endpoint());
        if (enabling) disable(ep);
        var unchangedActive = task(active); var unchangedTerminal = task(terminal); var unchangedForeign = task(foreign);
        var before = database();
        var workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(3));
        CountDownLatch eventHeld = new CountDownLatch(1), taskHeld = new CountDownLatch(1);
        CountDownLatch releaseEvent = new CountDownLatch(1), releaseTask = new CountDownLatch(1);
        AtomicReference<Long> eventBlocker = new AtomicReference<>(), taskBlocker = new AtomicReference<>(), batchConnection = new AtomicReference<>();
        // 独立单行探针只阻塞业务路径；不执行状态转换，也不反向申请其他业务锁。
        var eventProbe = workers.submit(() -> hold("event", second.event, eventBlocker, eventHeld, releaseEvent));
        var taskProbe = workers.submit(() -> hold("delivery_task", second.task, taskBlocker, taskHeld, releaseTask));
        try {
            assertTrue(eventHeld.await(5, TimeUnit.SECONDS)); assertTrue(taskHeld.await(5, TimeUnit.SECONDS));
            var batch = workers.submit(() -> transaction().execute(tx -> {
                batchConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                if (enabling) enable(ep); else disable(ep); return true;
            }));
            race.awaitWait(batchConnection, eventBlocker, "event", second.event, batch);
            assertTrue(race.holds(batchConnection.get(), "endpoint", ep));
            assertTrue(race.holds(batchConnection.get(), "event", first.event));
            assertTrue(race.holds(batchConnection.get(), "delivery_task", fresh));
            assertFalse(race.holds(batchConnection.get(), "delivery_task", second.task));
            assertFalse(race.holds(batchConnection.get(), "event", third.event));
            sameRows(before, database());
            releaseEvent.countDown(); eventProbe.get(5, TimeUnit.SECONDS);
            race.awaitWait(batchConnection, taskBlocker, "delivery_task", second.task, batch);
            assertTrue(race.holds(batchConnection.get(), "event", second.event));
            assertFalse(race.holds(batchConnection.get(), "event", third.event));
            sameRows(before, database());
            releaseTask.countDown(); taskProbe.get(5, TimeUnit.SECONDS); assertEquals(true, batch.get(10, TimeUnit.SECONDS));
        } finally {
            releaseEvent.countDown(); releaseTask.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
        consistent(first, enabling ? "PENDING" : "PAUSED"); consistent(second, enabling ? "PENDING" : "PAUSED");
        consistent(third, enabling ? "WAIT_RETRY" : "PAUSED");
        assertEquals(BASE.atOffset(ZoneOffset.UTC).toLocalDateTime().plusSeconds(10), time(third, enabling ? "next_run_at" : "resume_due_at"));
        same(old, task(first)); sameRows(oldHistory, attempts(first)); same(unchangedActive, task(active));
        same(unchangedTerminal, task(terminal)); same(unchangedForeign, task(foreign));
        assertEquals(activeClaim.claimToken(), task(active).get("claim_token")); assertTrue(attempts(second).isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?", Long.class, fresh));
    }

    static boolean hold(String table, long id, AtomicReference<Long> connection, CountDownLatch held, CountDownLatch release) {
        return Boolean.TRUE.equals(transaction().execute(tx -> {
            connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", Long.class, id); held.countDown();
            try { assertTrue(release.await(12, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return true;
        }));
    }
    static String id() { return UUID.randomUUID().toString(); }
    static long endpoint() {
        return context.getBean(EndpointSecretProvisioningService.class).createExternal("cross-flow",
                "https://hooks.example.com/original", 5000, true, actor, id()).configuration().id();
    }
    static Fixture pending(long ep) {
        String external = id();
        context.getBean(EventCreationService.class).create(external, "order.paid", ep,
                new EventPayloadCanonicalizer().canonicalize(JsonNodeFactory.instance.nullNode())).orElseThrow();
        long pk = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, external);
        return new Fixture(ep, pk, jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?", Long.class, pk), external);
    }
    static Fixture retry(long ep) { Fixture f = pending(ep); ClaimedTask c = claim(f); assertTrue(complete(f, c, 500)); return f; }
    static void oneAttemptPolicy(Fixture f) {
        // 历史策略差异夹具；正常领取与完成仍走正式服务。
        jdbc.update("UPDATE delivery_task SET max_attempts=1 WHERE id=?", f.task);
    }
    static ClaimedTask claim(Fixture f) { return context.getBean(DeliveryClaimService.class).claim(f.task).orElseThrow(); }
    static boolean complete(Fixture f, ClaimedTask c, int status) { return context.getBean(DeliveryCompletionService.class).complete(f.task, c.attemptNo(), c.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(status, "controlled")); }
    static boolean recover(Fixture f, ClaimedTask c) { return context.getBean(DeliveryRecoveryService.class).recover(f.task, c.claimToken()); }
    static DeliveryRedeliveryService.TaskSummary redeliver(Fixture f) { return context.getBean(DeliveryRedeliveryService.class).redeliver(f.external, actor, id()); }
    static void enable(long ep) { context.getBean(EndpointEnableService.class).enable(ep, actor, id()).orElseThrow(); }
    static void disable(long ep) { context.getBean(EndpointDisableService.class).disable(ep, actor, id()).orElseThrow(); }
    static Map<String,Object> task(Fixture f) { return jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task); }
    static Map<String,Object> event(Fixture f) { return jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.event); }
    static LocalDateTime time(Fixture f, String column) { return jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id=?", (rs, n) -> rs.getObject(1, LocalDateTime.class), f.task); }
    static List<Map<String,Object>> attempts(Fixture f) { return jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no", f.task); }
    static List<Map<String,Object>> audits() { return jdbc.queryForList("SELECT * FROM audit_log ORDER BY id"); }
    static List<Map<String,Object>> database() {
        var rows = new ArrayList<Map<String,Object>>();
        for (String table : List.of("endpoint", "event", "delivery_task", "delivery_attempt", "audit_log")) rows.addAll(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return rows;
    }
    static void consistent(Fixture f, String expected) {
        assertEquals(expected, event(f).get("current_status"));
        assertEquals(expected, jdbc.queryForObject("SELECT status FROM delivery_task WHERE event_pk=? AND round_no=?", String.class, f.event, event(f).get("latest_round_no")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM event e LEFT JOIN delivery_task t ON t.event_pk=e.id AND t.round_no=e.latest_round_no WHERE t.id IS NULL OR t.status<>e.current_status", Long.class));
    }
    static void interrupted(Fixture f) {
        assertEquals(1, attempts(f).size()); var a = attempts(f).getFirst();
        assertEquals("UNKNOWN", a.get("result")); assertEquals("EXECUTOR_INTERRUPTED", a.get("error_code"));
        assertEquals("执行器中断，HTTP结果未知", a.get("error_message")); assertNull(a.get("http_status")); assertNull(a.get("response_excerpt"));
        assertNotNull(a.get("ended_at")); assertNotNull(a.get("duration_ms"));
    }
    static void snapshot(Map<String,Object> before, Map<String,Object> after) {
        for (String column : List.of("event_pk", "round_no", "max_attempts", "attempt_count", "retry_delay_1_s", "retry_delay_2_s", "target_type_snapshot", "url_snapshot", "timeout_ms_snapshot", "created_at")) assertEquals(before.get(column), after.get(column), column);
        assertArrayEquals((byte[]) before.get("secret_ciphertext_snapshot"), (byte[]) after.get("secret_ciphertext_snapshot"));
    }
    static void same(Map<String,Object> before, Map<String,Object> after) { DeliveryRedeliveryIntegrationTests.same(before, after); }
    static void sameRows(List<Map<String,Object>> before, List<Map<String,Object>> after) {
        assertEquals(before.size(), after.size()); for (int i = 0; i < before.size(); i++) same(before.get(i), after.get(i));
    }
    static TransactionTemplate transaction() {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(20); return tx;
    }
    record Fixture(long endpoint, long event, long task, String external) { }
}
