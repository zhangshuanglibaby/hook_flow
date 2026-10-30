package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.audit.application.AuditRetentionService;
import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointEnableService;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.event.application.*;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.simulator.application.*;
import com.hookflow.support.MutableClock;
import com.hookflow.support.MySqlRowLockRace;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class RetentionIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant OLD = Instant.parse("2026-09-01T00:00:00.123Z");
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock retentionClock() { return new MutableClock(OLD); }
        @Bean @Primary ObservedQueries retentionQueries(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { return new ObservedQueries(jdbc); }
        @Bean @Primary ObservedReception retentionReception(SimulatorProcessingService processing, SimulatorResponseDelay delay) {
            return new ObservedReception(processing, delay);
        }
    }
    static class ObservedQueries extends com.hookflow.event.infrastructure.EventQueryRepository {
        private final AtomicReference<Runnable> hook = new AtomicReference<>();
        ObservedQueries(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { super(jdbc); }
        AtomicReference<Runnable> hook() { return hook; }
        @Override public long count(EventQueryService.Filter filter) { long count = super.count(filter); var action = hook.getAndSet(null); if (action != null) action.run(); return count; }
    }
    static class ObservedReception extends com.hookflow.simulator.api.SimulatorVerifiedReception {
        private final AtomicReference<Runnable> hook = new AtomicReference<>();
        ObservedReception(SimulatorProcessingService processing, SimulatorResponseDelay delay) { super(processing, delay); }
        AtomicReference<Runnable> hook() { return hook; }
        @Override public com.hookflow.simulator.domain.SimulatorConfiguration.Decision acknowledge(String id, Long expectedPk) {
            var action = hook.getAndSet(null); if (action != null) action.run();
            return super.acknowledge(id, expectedPk);
        }
    }

    @AfterEach void clear() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config", "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint")) jdbc.update("DELETE FROM " + table);
    }

    @Test void thirtyDaysIsInclusiveAndUsesLatestTerminalNotCreationOrOldRound() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long success = f.event("success", endpoint); f.finish("success", 200);
            long failed = f.event("failed", endpoint); f.finish("failed", 404);
            long redone = f.event("new-round", endpoint); f.finish("new-round", 404);
            f.clock.setInstant(OLD.plus(Duration.ofDays(29))); f.redeliver("new-round"); f.finish("new-round", 200);
            f.clock.setInstant(OLD.plus(Duration.ofDays(30)).minusMillis(1)); assertFalse(f.retention().deleteExpired(success)); assertFalse(f.retention().deleteExpired(failed));
            f.clock.advance(Duration.ofMillis(1)); assertTrue(f.retention().deleteExpired(success)); assertTrue(f.retention().deleteExpired(failed)); assertFalse(f.retention().deleteExpired(redone));
            assertEquals(2, f.count("delivery_task")); assertEquals(2, f.count("delivery_attempt")); assertEquals(1, f.count("event"));
            f.clock.setInstant(OLD.plus(Duration.ofDays(59))); assertTrue(f.retention().deleteExpired(redone));
            assertEquals(0, f.count("event")); assertEquals(0, f.count("delivery_task")); assertEquals(0, f.count("delivery_attempt")); assertEquals(1, f.count("endpoint"));
        }
    }

    @Test void unfinishedPausedAndInconsistentActiveHistoryAreNeverPurged() throws Exception {
        try (var f = new Fixture(false)) {
            long a = f.endpoint(), b = f.endpoint();
            var pending = f.event("pending", a); var delivering = f.event("delivering", a); f.claim("delivering");
            var retry = f.event("retry", a); f.finish("retry", 500);
            var paused = f.event("paused", b); f.context.getBean(EndpointDisableService.class).disable(b, f.actor, UUID.randomUUID().toString());
            var malformed = f.event("abnormal", a); var old = f.claim("abnormal"); f.complete(old, 404); f.redeliver("abnormal"); f.finish("abnormal", 200);
            // 异常历史夹具：旧轮残留活跃任务或STARTED，不能当作正常写流程。
            f.jdbc.update("UPDATE delivery_task SET status='PENDING',finished_at=NULL,next_run_at=? WHERE id=?", f.now(), old.taskId());
            f.clock.setInstant(OLD.plus(Duration.ofDays(31))); var before = f.snapshot();
            for (long id : List.of(pending, delivering, retry, paused, malformed)) assertFalse(f.retention().deleteExpired(id));
            assertEquals(before, f.snapshot());
            f.jdbc.update("UPDATE delivery_task SET status='FINAL_FAILED',finished_at=? WHERE id=?", LocalDateTime.ofInstant(OLD, ZoneOffset.UTC), old.taskId());
            f.jdbc.update("UPDATE delivery_attempt SET result='STARTED',ended_at=NULL,duration_ms=NULL WHERE task_id=?", old.taskId());
            assertFalse(f.retention().deleteExpired(malformed));
        }
    }

    @Test void failedParentDeleteRollsBackAttemptsTasksAndSimulatorState() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long event = f.event("rollback", endpoint); f.process("rollback"); f.finish("rollback", 200);
            f.clock.setInstant(OLD.plus(Duration.ofDays(30))); var before = f.snapshot();
            f.jdbc.execute("CREATE TABLE p905_block (event_pk BIGINT PRIMARY KEY, FOREIGN KEY(event_pk) REFERENCES event(id)) ENGINE=InnoDB"); f.jdbc.update("INSERT INTO p905_block VALUES (?)", event);
            try { assertThrows(RuntimeException.class, () -> f.retention().deleteExpired(event)); assertEquals(before, f.snapshot()); }
            finally { f.jdbc.execute("DROP TABLE p905_block"); }
            assertTrue(f.retention().deleteExpired(event)); assertEquals(0, f.count("simulator_event_state")); assertEquals(1, f.count("endpoint"));
        }
    }

    @Test void simulatorStateLivesWithActiveEventAndOldLogsAndAuditAreBoundedIndependently() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); f.event("active", endpoint); f.process("active");
            // 仅日志容量夹具；正常处理/幂等状态来自正式处理能力。
            for (int i = 0; i < 105; i++) {
                f.jdbc.update("INSERT INTO simulator_request_log(event_id,received_at,signature_valid,planned_status,duplicate_processed,config_mode) VALUES(NULL,?,0,401,0,NULL)", f.now());
                f.jdbc.update("INSERT INTO audit_log(actor_user_id,action,object_type,object_id,occurred_at,request_id) VALUES(?,'ENDPOINT_UPDATED','ENDPOINT','1',?,?)", f.actor, f.now(), UUID.randomUUID().toString());
            }
            f.clock.setInstant(OLD.plus(Duration.ofDays(30)).minusMillis(1));
            assertEquals(0, f.context.getBean(SimulatorRetentionService.class).deleteRequests(f.now().minusDays(30)));
            assertEquals(0, f.context.getBean(AuditRetentionService.class).deleteExpired(f.now().minusDays(30)));
            f.clock.advance(Duration.ofMillis(1));
            assertEquals(100, f.context.getBean(SimulatorRetentionService.class).deleteRequests(f.now().minusDays(30)));
            assertEquals(100, f.context.getBean(AuditRetentionService.class).deleteExpired(f.now().minusDays(30)));
            f.cleanup().cleanExpired(); assertEquals(0, f.count("simulator_request_log")); assertEquals(0, f.count("audit_log")); assertEquals(1, f.count("simulator_event_state"));
            assertTrue(f.process("active").duplicateProcessed());
        }
    }

    @Test void scannerLimitsSixteenAndCursorDoesNotStarveBehindInvalidCandidates() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint();
            for (int i = 0; i < 35; i++) { f.event("batch-" + i, endpoint); f.finish("batch-" + i, 200); }
            f.clock.setInstant(OLD.plus(Duration.ofDays(30))); f.cleanup().cleanExpired(); assertEquals(19, f.count("event"));
            f.cleanup().cleanExpired(); assertEquals(3, f.count("event")); f.cleanup().cleanExpired(); assertEquals(0, f.count("event"));
            f.clock.setInstant(OLD);
            for (int i = 0; i < 17; i++) { f.event("blocked-" + i, endpoint); f.finish("blocked-" + i, 200); }
            // 明确异常终态读模型夹具，前16条任务仍非终态，最后一条应能在下一批清理。
            f.jdbc.update("UPDATE delivery_task SET status='PENDING',finished_at=NULL,next_run_at=? WHERE event_pk IN (SELECT id FROM event WHERE event_id <> 'blocked-16')", f.now());
            f.clock.setInstant(OLD.plus(Duration.ofDays(30))); f.cleanup().cleanExpired(); assertEquals(17, f.count("event")); f.cleanup().cleanExpired(); assertEquals(16, f.count("event"));
        }
    }

    @Test void redeliveryAndCleanupSerializeBothWaysUsingRealEndpointLockEvidence() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long first = f.event("redelivery-wins", endpoint); f.finish("redelivery-wins", 404);
            long second = f.event("cleanup-wins", endpoint); f.finish("cleanup-wins", 404);
            f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            assertEquals(false, f.race().ordered(endpoint, () -> f.redeliver("redelivery-wins"), () -> f.retention().deleteExpired(first), () -> assertEquals(2, f.count("event"))));
            assertEquals("PENDING", f.jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, first));
            var result = f.race().ordered(endpoint, () -> f.retention().deleteExpired(second), () -> {
                try { return f.redeliver("cleanup-wins"); } catch (RuntimeException exception) { return exception; }
            }, () -> assertEquals(2, f.count("event")));
            assertInstanceOf(RuntimeException.class, result); assertEquals(1, f.count("event")); assertEquals(1, f.count("endpoint"));
        }
    }

    @Test void simulatorProcessingEventShareLockPreventsStateDeletionUntilReceptionCommits() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long event = f.event("sim-race", endpoint); f.finish("sim-race", 200); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            var changed = new CountDownLatch(1); var release = new CountDownLatch(1); var blocker = new AtomicReference<Long>(); var waiter = new AtomicReference<Long>();
            var pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
            var first = pool.submit(() -> f.tx().execute(status -> { blocker.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); f.process("sim-race"); changed.countDown(); waitLatch(release); return true; }));
            try {
                assertTrue(changed.await(5, TimeUnit.SECONDS)); var second = pool.submit(() -> f.tx().execute(status -> { waiter.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); return f.retention().deleteExpired(event); }));
                f.race().awaitWait(waiter, blocker, "event", event, second); assertEquals(0, f.observer.queryForObject("SELECT COUNT(*) FROM simulator_event_state", Long.class));
                release.countDown(); first.get(5, TimeUnit.SECONDS); assertEquals(true, second.get(5, TimeUnit.SECONDS));
                assertEquals(0, f.count("event")); assertEquals(0, f.count("simulator_event_state"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> f.process("sim-race")); assertEquals(0, f.count("simulator_event_state"));
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS)); }
        }
    }

    @Test void formalHttpQueriesAndOverviewStayConsistentAcrossAtomicRemovalAndIdCanBeReused() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long event = f.event("query-deleted", endpoint); f.process("query-deleted"); f.finish("query-deleted", 200); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            String token = f.login(); var before = f.get(token, "/api/v1/admin/events/query-deleted"); assertEquals(200, before.statusCode());
            var tx = f.tx(); tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            try { tx.executeWithoutResult(status -> {
                assertEquals(1, f.count("event"));
                try { assertTrue(pool.submit(() -> f.retention().deleteExpired(event)).get(4, TimeUnit.SECONDS)); } catch (Exception exception) { throw new IllegalStateException(exception); }
                assertEquals(1, f.count("event")); assertEquals(1, f.count("delivery_task")); assertEquals(1, f.count("delivery_attempt"));
            }); } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS)); }
            assertEquals(404, f.get(token, "/api/v1/admin/events/query-deleted").statusCode());
            var overview = f.mapper.readTree(f.get(token, "/api/v1/admin/overview?from=" + OLD + "&to=" + f.clock.instant().plusSeconds(1)).body());
            assertEquals(0, overview.path("eventCount").asLong()); assertEquals(0, overview.path("attemptCount").asLong());
            assertEquals(0, f.mapper.readTree(f.get(token, "/api/v1/admin/deliveries").body()).path("total").asLong());
            f.event("query-deleted", endpoint); assertFalse(f.process("query-deleted").duplicateProcessed());
        }
    }

    @Test void realHttpCountAndItemsKeepPreDeletionSnapshotAndNextRequestIsEmpty() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long event = f.event("snapshot-delete", endpoint); f.finish("snapshot-delete", 200);
            f.clock.setInstant(OLD.plus(Duration.ofDays(30))); String token = f.login();
            var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
            var slot = f.context.getBean(ObservedQueries.class).hook(); slot.set(() -> { reached.countDown(); waitLatch(release); });
            var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            try {
                var response = pool.submit(() -> f.get(token, "/api/v1/admin/events")); assertTrue(reached.await(4, TimeUnit.SECONDS));
                assertTrue(f.retention().deleteExpired(event)); release.countDown(); var result = response.get(5, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode()); var page = f.mapper.readTree(result.body()); assertEquals(1, page.path("total").asLong()); assertEquals(1, page.path("items").size());
                assertEquals("snapshot-delete", page.path("items").get(0).path("eventId").asString());
                assertEquals(0, f.mapper.readTree(f.get(token, "/api/v1/admin/events").body()).path("total").asLong());
            } finally { release.countDown(); slot.set(null); pool.shutdownNow(); assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS)); }
        }
    }

    @Test void duplicateCleanupAndDisableUseSameLockOrderAndRetainEndpoint() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long event = f.event("twice", endpoint); f.finish("twice", 200); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            assertEquals(false, f.race().ordered(endpoint, () -> f.retention().deleteExpired(event), () -> f.retention().deleteExpired(event), () -> assertEquals(1, f.count("event"))));
            assertEquals(0, f.count("event")); assertEquals(1, f.count("endpoint"));
            f.clock.setInstant(OLD); long another = f.event("disabled-old", endpoint); f.finish("disabled-old", 404); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            assertEquals(true, f.race().ordered(endpoint, () -> f.context.getBean(EndpointDisableService.class).disable(endpoint, f.actor, UUID.randomUUID().toString()), () -> f.retention().deleteExpired(another), () -> assertEquals(1, f.count("event"))));
            assertEquals(1, f.count("endpoint")); assertEquals(0, f.count("event"));
        }
    }

    @Test void verifiedOldHttpRequestCannotCreateStateForReusedEventIdAfterCleanup() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long original = f.event("reused", endpoint); f.finish("reused", 200);
            f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            byte[] cipher = f.jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs,row) -> rs.getBytes(1), endpoint);
            String secret = f.context.getBean(com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher.class).decryptForDelivery(cipher);
            byte[] body = "{\"fictional\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String timestamp = f.clock.instant().toString();
            String signature = f.context.getBean(com.hookflow.delivery.infrastructure.DeliveryRequestSigner.class).sign("reused", timestamp, body, secret);
            f.context.getBean(ObservedReception.class).hook().set(() -> {
                assertTrue(f.retention().deleteExpired(original)); long next = f.event("reused", endpoint); assertNotEquals(original, next);
            });
            var request = HttpRequest.newBuilder(URI.create(f.base() + "/internal/hookflow/simulator/receive"))
                    .timeout(Duration.ofSeconds(5)).header("X-HookFlow-Event-Id", "reused")
                    .header("X-HookFlow-Timestamp", timestamp).header("X-HookFlow-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = f.http.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(401, response.statusCode(), response.body());
            assertEquals(1, f.count("event")); assertEquals(0, f.count("simulator_event_state")); assertEquals(0, f.count("simulator_request_log"));
            assertFalse(f.process("reused").duplicateProcessed());
        }
    }

    @Test void scannerContinuesAfterOneDatabaseDeleteFailureWithoutLeavingPartialHistory() throws Exception {
        try (var f = new Fixture(false)) {
            long endpoint = f.endpoint(); long blocked = f.event("blocked-parent", endpoint); f.finish("blocked-parent", 200);
            f.event("removable", endpoint); f.finish("removable", 200); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            f.jdbc.execute("CREATE TABLE p905_block (event_pk BIGINT PRIMARY KEY, FOREIGN KEY(event_pk) REFERENCES event(id)) ENGINE=InnoDB"); f.jdbc.update("INSERT INTO p905_block VALUES (?)", blocked);
            try {
                f.cleanup().cleanExpired(); assertEquals(1, f.count("event")); assertEquals(1, f.count("delivery_task")); assertEquals(1, f.count("delivery_attempt"));
                assertEquals("blocked-parent", f.jdbc.queryForObject("SELECT event_id FROM event", String.class));
            } finally { f.jdbc.execute("DROP TABLE p905_block"); }
            f.cleanup().cleanExpired(); assertEquals(0, f.count("event"));
        }
    }

    @Test void candidateReadFailureIsSafelyLoggedAndOtherRetentionBatchesStillRun() throws Exception {
        try (var f = new Fixture(false)) {
            f.endpoint();
            f.jdbc.update("INSERT INTO simulator_request_log(event_id,received_at,signature_valid,planned_status,duplicate_processed,config_mode) VALUES(NULL,?,0,401,0,NULL)", f.now());
            f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            // 新记录不得因同批有旧记录而被提前删除。
            f.jdbc.update("INSERT INTO simulator_request_log(event_id,received_at,signature_valid,planned_status,duplicate_processed,config_mode) VALUES(NULL,?,0,401,0,NULL)", f.now());
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RetentionCleanup.class);
            var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            f.jdbc.execute("RENAME TABLE event TO p905_unavailable");
            try {
                assertDoesNotThrow(() -> f.cleanup().cleanExpired());
                assertEquals(0, f.count("audit_log")); assertEquals(1, f.count("simulator_request_log"));
                assertEquals(f.now(), f.jdbc.queryForObject("SELECT received_at FROM simulator_request_log", LocalDateTime.class));
                assertEquals(1, logs.list.size()); assertTrue(logs.list.getFirst().getFormattedMessage().startsWith("eventRetentionScanFailed exceptionType="));
                assertFalse(logs.list.getFirst().getFormattedMessage().contains("p905_unavailable")); assertFalse(logs.list.getFirst().getFormattedMessage().contains("SELECT")); assertNull(logs.list.getFirst().getThrowableProxy());
            } finally { f.jdbc.execute("RENAME TABLE p905_unavailable TO event"); logger.detachAppender(logs); logs.stop(); }
            assertEquals(1, f.count("endpoint"));
        }
    }

    @Test void formalSchedulerRunsWithoutManualCleanupAndCallerTransactionIsRejected() throws Exception {
        try (var f = new Fixture(true)) {
            long endpoint = f.endpoint(); f.event("scheduled", endpoint); f.finish("scheduled", 200); f.clock.setInstant(OLD.plus(Duration.ofDays(30)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (f.count("event") != 0) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10); }
            assertEquals(1, f.count("endpoint"));
            assertThrows(IllegalStateException.class, () -> f.tx().executeWithoutResult(status -> f.cleanup().cleanExpired()));
        }
    }

    static void waitLatch(CountDownLatch latch) { try { assertTrue(latch.await(8, TimeUnit.SECONDS)); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); } }
    static class Fixture implements AutoCloseable {
        final ConfigurableApplicationContext context; final JdbcTemplate jdbc, observer; final MutableClock clock; final long actor;
        final JsonMapper mapper = JsonMapper.builder().build(); final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Fixture(boolean scheduled) {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            context = new SpringApplicationBuilder(HookflowBackendApplication.class, Configuration.class).run(
                    "--spring.profiles.active=" + (scheduled ? "default" : "p2-login-test"), "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p905-admin", "--HOOKFLOW_ADMIN_PASSWORD=p905-password", "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key),
                    "--hookflow.retention.initial-delay-ms=200", "--hookflow.retention.fixed-delay-ms=200", "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc = context.getBean(JdbcTemplate.class); clock = context.getBean(MutableClock.class); observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())); observer.setQueryTimeout(3);
            actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='p905-admin'", Long.class);
        }
        LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
        long endpoint() { return context.getBean(EndpointSecretProvisioningService.class).createSimulator("p905", 5000, actor, UUID.randomUUID().toString()).id(); }
        long event(String id, long endpoint) { context.getBean(EventCreationService.class).create(id, "retention", endpoint, new EventPayloadCanonicalizer().canonicalize(mapper.readTree("{\"fictional\":true}"))).orElseThrow(); return jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, id); }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no", Long.class, id); }
        DeliveryClaimService.ClaimedTask claim(String id) { return context.getBean(DeliveryClaimService.class).claim(task(id)).orElseThrow(); }
        void complete(DeliveryClaimService.ClaimedTask claim, int status) { assertTrue(context.getBean(DeliveryCompletionService.class).complete(claim.taskId(), claim.attemptNo(), claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(status, "fictional"))); }
        void finish(String id, int status) { complete(claim(id), status); }
        Object redeliver(String id) { return context.getBean(DeliveryRedeliveryService.class).redeliver(id, actor, UUID.randomUUID().toString()); }
        com.hookflow.simulator.domain.SimulatorConfiguration.Decision process(String id) { return context.getBean(SimulatorProcessingService.class).receiveVerified(id); }
        EventRetentionService retention() { return context.getBean(EventRetentionService.class); }
        RetentionCleanup cleanup() { return context.getBean(RetentionCleanup.class); }
        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
        TransactionTemplate tx() { var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx; }
        MySqlRowLockRace race() { return new MySqlRowLockRace(jdbc, observer, this::tx); }
        Map<String,Object> snapshot() { var state = new LinkedHashMap<String,Object>(); for (String table : List.of("event", "delivery_task", "delivery_attempt", "endpoint", "audit_log", "simulator_event_state", "simulator_request_log")) state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1").stream().map(row -> { var copy = new LinkedHashMap<>(row); copy.replaceAll((key,value) -> value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value); return copy; }).toList()); return state; }
        String login() throws Exception { var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login")).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"p905-admin\",\"password\":\"p905-password\"}")).build(), HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode()); return mapper.readTree(response.body()).path("token").asString(); }
        String base() { return "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"); }
        HttpResponse<String> get(String token, String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        @Override public void close() { http.close(); context.close(); }
    }
}
