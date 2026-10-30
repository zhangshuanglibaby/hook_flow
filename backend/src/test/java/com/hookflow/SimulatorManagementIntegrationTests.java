package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.delivery.application.DueDeliveryScanner;
import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.simulator.application.*;
import com.hookflow.simulator.domain.SimulatorConfiguration;
import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import com.hookflow.simulator.infrastructure.SimulatorRepository;
import com.hookflow.support.MutableClock;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class SimulatorManagementIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final String CONFIG = "/api/v1/admin/simulator/config", REQUESTS = "/api/v1/admin/simulator/requests";
    static final Instant NOW = Instant.parse("2026-10-30T02:30:00.123456Z");
    static final byte[] BODY = "{\"sensitivePayloadMarker\":\"p803-only-fictitious\"}".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary CountingClock managementClock() { return new CountingClock(); }
        @Bean @Primary ObservedDelay managementDelay() { return new ObservedDelay(); }
        @Bean @Primary ObservedRepository managementRepository(JdbcTemplate jdbc) { return new ObservedRepository(jdbc); }
    }
    static class CountingClock extends Clock {
        final MutableClock mutable = new MutableClock(NOW);
        final AtomicInteger reads = new AtomicInteger();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return mutable.withZone(zone); }
        @Override public Instant instant() { reads.incrementAndGet(); return mutable.instant(); }
        void advance(Duration duration) { mutable.advance(duration); }
        void set(Instant time) { mutable.setInstant(time); }
    }
    static class ObservedDelay extends SimulatorResponseDelay {
        final CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicInteger milliseconds = new AtomicInteger();
        @Override public void await(int ms) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (ms == 0) { super.await(0); return; }
            milliseconds.set(ms); started.countDown();
            try { super.await(ms); } finally { finished.countDown(); }
        }
    }
    static class ObservedRepository extends SimulatorRepository {
        final AtomicInteger writes = new AtomicInteger();
        ObservedRepository(JdbcTemplate jdbc) { super(jdbc); }
        @Override public void processOnce(String id, LocalDateTime now) { super.processOnce(id,now); writes.incrementAndGet(); }
        @Transactional(propagation = Propagation.SUPPORTS) public int writes() { return writes.get(); }
    }

    @AfterEach void clear() {
        var jdbc = independent();
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config",
                "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint")) jdbc.update("DELETE FROM " + table);
    }

    @Test void emptyGetPersistsUtcDefaultWithoutAuditAndExistingConfigurationSurvivesReadsAndNoOpPut() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(0,f.count("simulator_config")); long audits = f.count("audit_log");
            var initial = f.getConfig(); f.assertConfig(initial,"ALWAYS_200",0,0);
            assertEquals(f.now().toInstant(ZoneOffset.UTC),Instant.parse(initial.path("updatedAt").asString()));
            assertEquals(1,f.count("simulator_config")); assertEquals(audits,f.count("audit_log"));
            f.jdbc.update("DELETE FROM simulator_config");
            var defaultPut = f.put("ALWAYS_200",0,0); assertEquals(initial,f.json(defaultPut));
            f.assertNoAudit(defaultPut); assertEquals(audits,f.count("audit_log"));
            assertEquals(f.now(),independent().queryForObject("SELECT updated_at FROM simulator_config",LocalDateTime.class));
            f.clock.advance(Duration.ofSeconds(1)); assertEquals(initial,f.getConfig());
            var unchanged = f.put("ALWAYS_200",0,0); assertEquals(initial,f.json(unchanged));
            f.assertNoAudit(unchanged); assertEquals(audits,f.count("audit_log"));
            var changed = f.put("FAIL_FIRST_N",10,0); f.audit(changed);
            var saved = f.json(changed); f.clock.advance(Duration.ofSeconds(1));
            assertEquals(saved,f.getConfig()); assertEquals(saved,f.json(f.put("FAIL_FIRST_N",10,0)));
            assertEquals(audits+1,f.count("audit_log"));
            f.assertConfig(f.json(f.request("PUT",CONFIG,f.token,null,"{\"mode\":\"ALWAYS_500\"}")),"ALWAYS_500",0,0);
            f.assertConfig(f.json(f.request("PUT",CONFIG,f.token,null,"{\"mode\":\"FAIL_FIRST_N\"}")),"FAIL_FIRST_N",0,0);
            f.assertConfig(f.json(f.put("PROCESSED_THEN_DELAY",0,30000)),"PROCESSED_THEN_DELAY",0,30000);
            assertEquals(0,f.count("simulator_event_state")); assertEquals(0,f.count("simulator_request_log"));
        }
    }

    @Test void formalHttpAuthenticationStrictJsonPaginationAndRequestIdsNeverCreateSuccessAuditOnFailure() throws Exception {
        try (var f = new Fixture()) {
            for (String path : List.of(CONFIG,REQUESTS)) {
                f.error(f.request("GET",path,null,null,null),401);
                f.error(f.request("GET",path,null,f.business,null),403);
            }
            f.error(f.request("PUT",CONFIG,null,null,"{\"mode\":\"ALWAYS_500\"}"),401);
            f.error(f.request("PUT",CONFIG,null,f.business,"{\"mode\":\"ALWAYS_500\"}"),403);
            assertEquals(0,f.count("simulator_config"));
            for (String body : List.of("", "null", "[]", "true", "{}", "{", "{\"mode\":null}",
                    "{\"mode\":1}", "{\"mode\":true}", "{\"mode\":\"always_200\"}", "{\"mode\":\"INVALID\"}",
                    "{\"mode\":\"ALWAYS_200\",\"secret\":\"input-secret-marker\"}",
                    "{\"mode\":\"ALWAYS_200\",\"failFirstN\":1}", "{\"mode\":\"ALWAYS_500\",\"delayMs\":1}",
                    "{\"mode\":\"FAIL_FIRST_N\",\"delayMs\":1}", "{\"mode\":\"PROCESSED_THEN_DELAY\"}",
                    "{\"mode\":\"PROCESSED_THEN_DELAY\",\"delayMs\":1,\"failFirstN\":1}")) {
                f.error(f.request("PUT",CONFIG,f.token,null,body),400);
            }
            for (String raw : List.of("null","-1","11","1.0","1e0","\"1\"","true","2147483648","[]","{}"))
                f.error(f.request("PUT",CONFIG,f.token,null,"{\"mode\":\"FAIL_FIRST_N\",\"failFirstN\":"+raw+"}"),400);
            for (String raw : List.of("null","-1","0","30001","1.0","1e0","\"1\"","false","2147483648","[]","{}"))
                f.error(f.request("PUT",CONFIG,f.token,null,"{\"mode\":\"PROCESSED_THEN_DELAY\",\"delayMs\":"+raw+"}"),400);
            for (String query : List.of("page=0","page=-1","page=1.0","page=2147483648","page=99999999999999999999999",
                    "page=", "page=true", "size=0","size=101","size=2147483648","size=1.0","size=null"))
                f.error(f.request("GET",REQUESTS+"?"+query,f.token,null,null),400);
            assertEquals(0,f.count("simulator_config")); assertEquals(1,f.count("audit_log"));
            for (int n : new int[] {0,10}) f.assertConfig(f.json(f.put("FAIL_FIRST_N",n,0)),"FAIL_FIRST_N",n,0);
            for (int delay : new int[] {1,30000}) f.assertConfig(f.json(f.put("PROCESSED_THEN_DELAY",0,delay)),"PROCESSED_THEN_DELAY",0,delay);
            // 管理员身份或业务凭据不能替代内部 HMAC。
            f.error(f.request("POST",SimulatorInternalTargetRoute.PATH,f.token,null,"{}"),401);
            f.error(f.request("POST",SimulatorInternalTargetRoute.PATH,null,f.business,"{}"),401);
            assertEquals(0,f.count("simulator_event_state"));
        }
    }

    @Test void oneInjectedClockReadAndRealDatabaseFailuresKeepConfigurationAndAuditAtomicIncludingFirstInitialization() throws Exception {
        try (var f = new Fixture()) {
            f.clock.reads.set(0);
            var stored = f.management().update(new SimulatorConfiguration(Mode.FAIL_FIRST_N,10,0),f.actor,UUID.randomUUID().toString());
            assertEquals(1,f.clock.reads.get()); assertEquals(f.now(),stored.updatedAt());
            assertEquals(stored.updatedAt(),f.jdbc.queryForObject("SELECT occurred_at FROM audit_log WHERE action='SIMULATOR_CONFIG_UPDATED'",LocalDateTime.class));
            var before = f.configRow(); long audits = f.count("audit_log");
            var logger = (Logger) LoggerFactory.getLogger(com.hookflow.common.api.ApiExceptionHandler.class);
            var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
            try {
                f.jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT p803_reject_audit CHECK (action <> 'SIMULATOR_CONFIG_UPDATED' OR object_id <> '1' OR occurred_at <= '2026-10-30 02:30:00.123')");
                try {
                    f.clock.advance(Duration.ofMillis(1)); f.error(f.put("ALWAYS_500",0,0),500);
                    assertEquals(before,f.configRow()); assertEquals(audits,f.count("audit_log"));
                    f.jdbc.update("DELETE FROM simulator_config"); f.error(f.put("ALWAYS_500",0,0),500);
                    assertEquals(0,f.count("simulator_config")); assertEquals(audits,f.count("audit_log"));
                } finally { f.jdbc.execute("ALTER TABLE audit_log DROP CHECK p803_reject_audit"); }
                f.put("FAIL_FIRST_N",10,0); before=f.configRow(); audits=f.count("audit_log");
                f.jdbc.execute("ALTER TABLE simulator_config ADD CONSTRAINT p803_reject_config CHECK (mode <> 'ALWAYS_500')");
                try { f.error(f.put("ALWAYS_500",0,0),500); assertEquals(before,f.configRow()); assertEquals(audits,f.count("audit_log")); }
                finally { f.jdbc.execute("ALTER TABLE simulator_config DROP CHECK p803_reject_config"); }
                assertFalse(appender.list.isEmpty());
                for (var log : appender.list) { f.noSecrets(log.getFormattedMessage()); assertNull(log.getThrowableProxy()); }
            } finally { logger.detachAppender(appender); appender.stop(); }
            f.audit(f.put("ALWAYS_500",0,0));
        }
    }

    @Test void databaseReadAndDefaultInitializationFailuresReturnSafe500WithoutPretendingToPersistDefaults() throws Exception {
        try (var f = new Fixture()) {
            f.jdbc.execute("ALTER TABLE simulator_config ADD CONSTRAINT p803_reject_initial CHECK (mode <> 'ALWAYS_200')");
            try {
                f.error(f.request("GET",CONFIG,f.token,null,null),500);
                assertEquals(0,f.count("simulator_config")); assertEquals(0,f.auditCount());
            } finally { f.jdbc.execute("ALTER TABLE simulator_config DROP CHECK p803_reject_initial"); }
            f.getConfig();
            for (String table : List.of("simulator_config","simulator_request_log")) {
                f.jdbc.execute("RENAME TABLE " + table + " TO p803_unavailable");
                try { f.error(f.request("GET",table.equals("simulator_config")?CONFIG:REQUESTS,f.token,null,null),500); }
                finally { f.jdbc.execute("RENAME TABLE p803_unavailable TO " + table); }
            }
            f.assertConfig(f.getConfig(),"ALWAYS_200",0,0); assertEquals(0,f.page("").path("total").asInt());
            assertEquals(0,f.auditCount());
        }
    }

    @Test void requestPagesAreStableForEqualTimesWithRealSuccessFailureDuplicateRejectionsAndNullableFields() throws Exception {
        try (var f = new Fixture()) {
            var empty = f.page(""); assertEquals(1,empty.path("page").asInt()); assertEquals(20,empty.path("size").asInt());
            assertEquals(0,empty.path("total").asLong()); assertEquals(0,empty.path("items").size());
            String success = f.event(5000), failing = f.event(5000);
            f.put("ALWAYS_200",0,0); assertEquals(200,f.signed(success,false).statusCode());
            f.put("ALWAYS_500",0,0); assertEquals(500,f.signed(failing,false).statusCode());
            assertEquals(200,f.signed(success,false).statusCode());
            f.error(f.signed(success,true),401);
            f.error(f.signed(success,false,new byte[0]),400);
            // V1 允许尚未决定 planned_status；只为读模型保留一个显式可空历史夹具。
            f.jdbc.update("INSERT INTO simulator_request_log (event_id,received_at,signature_valid,planned_status,duplicate_processed,config_mode) VALUES (NULL,?,0,NULL,0,NULL)",f.now());
            for (int i=0;i<19;i++) f.error(f.signed(failing,true),401);
            assertEquals(25,f.count("simulator_request_log"));
            var expected=f.jdbc.queryForList("SELECT * FROM simulator_request_log ORDER BY received_at DESC,id DESC");
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(DISTINCT received_at) FROM simulator_request_log",Integer.class));
            var page=f.page(""); assertEquals(25,page.path("total").asLong()); assertEquals(20,page.path("items").size());
            var rest=f.page("?page=2"); assertEquals(5,rest.path("items").size());
            for (int i=0;i<25;i++) {
                var node = i<20 ? page.path("items").get(i) : rest.path("items").get(i-20);
                var row=expected.get(i); assertEquals(7,node.size()); assertEquals(((Number)row.get("id")).longValue(),node.path("id").asLong());
                if (row.get("event_id")==null) assertTrue(node.path("eventId").isNull()); else assertEquals(row.get("event_id"),node.path("eventId").asString());
                assertTrue(node.path("signatureValid").isBoolean()); assertTrue(node.path("duplicateProcessed").isBoolean());
                assertEquals(flag(row,"signature_valid"),node.path("signatureValid").asBoolean());
                assertEquals(flag(row,"duplicate_processed"),node.path("duplicateProcessed").asBoolean());
                if (row.get("planned_status")==null) assertTrue(node.path("plannedStatus").isNull()); else assertEquals(((Number)row.get("planned_status")).intValue(),node.path("plannedStatus").asInt());
                if (row.get("config_mode")==null) assertTrue(node.path("configMode").isNull()); else assertEquals(row.get("config_mode"),node.path("configMode").asString());
                assertEquals(f.now().toInstant(ZoneOffset.UTC),Instant.parse(node.path("receivedAt").asString()));
            }
            for (String query : List.of("?page=3","?page=2147483647&size=100")) {
                var last=f.page(query); assertEquals(25,last.path("total").asLong()); assertEquals(0,last.path("items").size());
            }
            assertEquals(1,f.page("?size=1").path("items").size()); assertEquals(25,f.page("?size=100").path("items").size());
            assertEquals(3,f.jdbc.queryForObject("SELECT SUM(received_count) FROM simulator_event_state",Integer.class));
            assertEquals(2,f.count("simulator_event_state"));
        }
    }

    @Test void concurrentPutsReallyWaitForSingletonLockAndNeverTearParametersOrMultiplyRows() throws Exception {
        try (var f = new Fixture()) {
            f.getConfig();
            for (int i=0;i<3;i++) {
                var a = new SimulatorConfiguration(Mode.FAIL_FIRST_N,10,0);
                var b = new SimulatorConfiguration(Mode.PROCESSED_THEN_DELAY,0,30000);
                long before=f.auditCount();
                f.ordered("simulator_config","1",() -> f.update(a),() -> f.update(b),() -> {
                    var committed=independent().queryForMap("SELECT * FROM simulator_config");
                    assertNotEquals("FAIL_FIRST_N",committed.get("mode"));
                });
                assertEquals(1,f.count("simulator_config")); f.assertConfig(f.getConfig(),"PROCESSED_THEN_DELAY",0,30000);
                assertEquals(before+2,f.auditCount());
                f.clock.advance(Duration.ofMillis(1));
            }
        }
    }

    @Test void emptyInitializationRacesBetweenManagementGetPutAndValidReceptionNeverOverwriteWinner() throws Exception {
        try (var f = new Fixture()) {
            String id=f.event(5000);
            var nonDefault=new SimulatorConfiguration(Mode.FAIL_FIRST_N,10,0);
            for (int scenario=0;scenario<7;scenario++) {
                f.jdbc.update("DELETE FROM simulator_config"); f.jdbc.update("DELETE FROM simulator_request_log"); f.jdbc.update("DELETE FROM simulator_event_state");
                int current=scenario; long audit=f.auditCount();
                Supplier<?> winner = switch(current) {
                    case 0, 5 -> () -> f.update(nonDefault);
                    case 1, 3 -> () -> f.processing().receiveVerified(id);
                    default -> () -> f.management().configuration();
                };
                Supplier<?> loser = switch(current) {
                    case 0, 2 -> () -> f.processing().receiveVerified(id);
                    case 1, 5, 6 -> () -> f.management().configuration();
                    default -> () -> f.update(nonDefault);
                };
                f.ordered("simulator_config","1",winner,loser,() -> assertEquals(0,independent().queryForObject("SELECT COUNT(*) FROM simulator_config",Integer.class)));
                assertEquals(1,f.count("simulator_config"));
                boolean defaults = current==1 || current==2 || current==6;
                f.assertConfig(f.getConfig(),defaults ? "ALWAYS_200" : "FAIL_FIRST_N",defaults ? 0 : 10,0);
                assertEquals(audit+(defaults ? 0:1),f.auditCount());
                if (current<4) {
                    assertEquals(1,f.count("simulator_request_log"));
                    assertEquals(current==0 ? "FAIL_FIRST_N":"ALWAYS_200",f.logs(id).getFirst().get("config_mode"));
                    assertEquals(current==0 ? 500:200,((Number)f.logs(id).getFirst().get("planned_status")).intValue());
                }
            }
        }
    }

    @Test void waitingReceptionKeepsReadSnapshotAndManagementPutDoesNotAcquireBusinessStateLock() throws Exception {
        try (var f = new Fixture()) {
            f.put("FAIL_FIRST_N",10,0); String id=f.event(5000);
            var result=f.ordered("simulator_event_state","'"+id+"'",() -> f.processing().receiveVerified(id),
                    () -> f.processing().receiveVerified(id),() -> {
                        try { f.audit(f.put("ALWAYS_200",0,0)); f.assertConfig(f.getConfig(),"ALWAYS_200",0,0); }
                        catch(Exception e) { throw new IllegalStateException(e); }
                    });
            assertEquals(500,((SimulatorConfiguration.Decision)result).plannedStatus());
            assertEquals(2,f.state(id).get("received_count")); assertNull(f.state(id).get("processed_at"));
            assertEquals(List.of("FAIL_FIRST_N","FAIL_FIRST_N"),f.logs(id).stream().map(row -> row.get("config_mode")).toList());
            assertEquals(200,f.signed(id,false).statusCode());
        }
    }

    @Test void formalPutAndReadDriveAlways200AndFailFirstModesWithoutResettingCountsOrProcessedMarker() throws Exception {
        try (var f = new Fixture()) {
            for (int n : new int[]{0,2}) {
                String mode=n==0?"ALWAYS_200":"FAIL_FIRST_N"; f.put(mode,n,0); f.assertConfig(f.getConfig(),mode,n,0);
                String id=f.event(5000); long task=f.task(id);
                for(int attempt=1;attempt<=n+1;attempt++) {
                    f.scan(); f.awaitTask(task,attempt<=n?"WAIT_RETRY":"SUCCESS");
                    f.assertAttempt(task,attempt,attempt<=n?"HTTP_FAILURE":"SUCCESS",attempt<=n?500:200);
                    assertEquals(attempt,f.state(id).get("received_count"));
                    if(attempt<=n) {
                        var state=f.state(id); var logs=f.logs(id); f.getConfig();
                        f.put("ALWAYS_500",0,0); f.put("FAIL_FIRST_N",n,0);
                        assertEquals(state,f.state(id)); assertEquals(logs,f.logs(id));
                        f.advanceToRetry(task,attempt,attempt==1?10:30);
                    }
                }
                f.consistent(task,"SUCCESS",n+1); var marker=f.state(id).get("processed_at");
                f.put("ALWAYS_500",0,0); assertEquals(200,f.signed(id,false).statusCode());
                f.put("PROCESSED_THEN_DELAY",0,30000); long began=System.nanoTime(); assertEquals(200,f.signed(id,false).statusCode());
                assertTrue(Duration.ofNanos(System.nanoTime()-began).toMillis()<1500);
                assertEquals(marker,f.state(id).get("processed_at")); f.verifyReadModel();
            }
            assertEquals(2,f.writes());
        }
    }

    @Test void formalAlways500ReachesSingleRoundLimitAndManualRoundPreservesFailFirstCumulativeIdentity() throws Exception {
        try(var f=new Fixture()) {
            f.put("ALWAYS_500",0,0); f.assertConfig(f.getConfig(),"ALWAYS_500",0,0);
            String id=f.event(5000); long old=f.task(id);
            for(int attempt=1;attempt<=3;attempt++) {
                f.scan(); f.awaitTask(old,attempt==3?"FINAL_FAILED":"WAIT_RETRY"); f.assertAttempt(old,attempt,"HTTP_FAILURE",500);
                if(attempt<3) f.advanceToRetry(old,attempt,attempt==1?10:30);
            }
            f.consistent(old,"FINAL_FAILED",3); var history=f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no",old);
            var state=f.state(id); var logs=f.logs(id); f.put("FAIL_FIRST_N",3,0); f.getConfig();
            assertEquals(state,f.state(id)); assertEquals(logs,f.logs(id));
            var round=f.request("POST","/api/v1/admin/events/"+id+"/redeliver",f.token,null,null); assertEquals(202,round.statusCode());
            long next=f.task(id); assertNotEquals(old,next); f.scan(); f.awaitTask(next,"SUCCESS"); f.consistent(next,"SUCCESS",1);
            assertEquals(2,f.jdbc.queryForObject("SELECT latest_round_no FROM event WHERE event_id=?",Integer.class,id));
            assertEquals(4,f.state(id).get("received_count")); assertEquals(1,f.writes());
            assertEquals(history,f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no",old));
            f.verifyReadModel();
        }
    }

    @Test void formalDelayTimeoutCommitsBeforeWaitAndPutDoesNotChangeAlreadyStartedDelayOrHoldBusinessLocks() throws Exception {
        try(var f=new Fixture()) {
            f.put("PROCESSED_THEN_DELAY",0,3500); f.assertConfig(f.getConfig(),"PROCESSED_THEN_DELAY",0,3500);
            String id=f.event(1200); long task=f.task(id); f.scan();
            var delay=f.context.getBean(ObservedDelay.class); assertTrue(delay.started.await(3,TimeUnit.SECONDS));
            assertEquals(3500,delay.milliseconds.get()); assertNotNull(f.state(id).get("processed_at"));
            assertEquals(200,((Number)f.logs(id).getFirst().get("planned_status")).intValue());
            var marker=f.state(id).get("processed_at");
            try(var connection=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).getConnection()) {
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("SELECT received_count FROM simulator_event_state WHERE event_id=? FOR UPDATE NOWAIT")) {
                    statement.setQueryTimeout(2); statement.setString(1,id); try(var rs=statement.executeQuery()) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); }
                } finally { connection.rollback(); }
            }
            f.audit(f.put("ALWAYS_500",0,0)); f.getConfig();
            assertEquals(1,delay.finished.getCount()); assertEquals(3500,delay.milliseconds.get());
            f.awaitTask(task,"WAIT_RETRY"); f.assertAttempt(task,1,"UNKNOWN",null);
            var first=f.jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=1",task);
            assertNull(first.get("response_excerpt")); f.advanceToRetry(task,1,10); f.scan(); f.awaitTask(task,"SUCCESS");
            assertEquals(1,delay.finished.getCount(),"后续 PUT 和正式重试不能等待原响应延迟结束");
            f.assertAttempt(task,2,"SUCCESS",200); f.consistent(task,"SUCCESS",2);
            assertEquals(2,f.state(id).get("received_count")); assertEquals(marker,f.state(id).get("processed_at")); assertEquals(1,f.writes());
            assertEquals(List.of("PROCESSED_THEN_DELAY","ALWAYS_500"),f.logs(id).stream().map(row->row.get("config_mode")).toList());
            assertTrue(f.logs(id).stream().allMatch(row->((Number)row.get("planned_status")).intValue()==200));
            f.verifyReadModel(); assertTrue(delay.finished.await(5,TimeUnit.SECONDS));
            assertEquals(first,f.jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=1",task));
        }
    }

    @Test void fullApplicationContextRestartPreservesManagementConfigurationCountersAndRequestReadModel() throws Exception {
        try(var f=new Fixture()) {
            f.put("FAIL_FIRST_N",2,0); String id=f.event(5000); assertEquals(500,f.signed(id,false).statusCode());
            var config=f.getConfig(); var page=f.page(""); f.restart();
            assertEquals(config,f.getConfig()); assertEquals(page,f.page("")); assertEquals(1,f.state(id).get("received_count"));
            assertEquals(500,f.signed(id,false).statusCode()); assertEquals(200,f.signed(id,false).statusCode());
            var marker=f.state(id).get("processed_at"); f.put("ALWAYS_500",0,0); var all=f.page(""); f.restart();
            assertEquals(all,f.page("")); f.assertConfig(f.getConfig(),"ALWAYS_500",0,0); assertEquals(200,f.signed(id,false).statusCode());
            assertEquals(4,f.state(id).get("received_count")); assertEquals(marker,f.state(id).get("processed_at")); f.verifyReadModel();
        }
    }

    static boolean flag(Map<String,Object> row,String key) { Object value=row.get(key); return value instanceof Boolean b ? b : ((Number)value).intValue()!=0; }
    static JdbcTemplate independent() { var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())); jdbc.setQueryTimeout(3); return jdbc; }
    static void await(BooleanSupplier condition,long milliseconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"有界数据库等待未达成");
    }

    static class Fixture implements AutoCloseable {
        final String encryption;
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ConfigurableApplicationContext context; JdbcTemplate jdbc; JsonMapper mapper; CountingClock clock;
        String base,token,business; long actor;
        Fixture() throws Exception { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); encryption=Base64.getEncoder().encodeToString(bytes); start(); }
        void start() throws Exception {
            context=new SpringApplicationBuilder(HookflowBackendApplication.class,Configuration.class).run(
                    "--spring.profiles.active=p2-login-test","--server.port=0","--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL="+MYSQL.getJdbcUrl(),"--DB_USERNAME="+MYSQL.getUsername(),"--DB_PASSWORD="+MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p803-admin","--HOOKFLOW_ADMIN_PASSWORD=p803-admin-password","--HOOKFLOW_SECRET_ENCRYPTION_KEY="+encryption,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc=context.getBean(JdbcTemplate.class); mapper=context.getBean(JsonMapper.class); clock=context.getBean(CountingClock.class);
            base="http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port");
            var login=request("POST","/api/v1/auth/login",null,null,"{\"username\":\"p803-admin\",\"password\":\"p803-admin-password\"}");
            assertEquals(200,login.statusCode()); token=json(login).path("token").asString();
            actor=jdbc.queryForObject("SELECT id FROM app_user WHERE username='p803-admin'",Long.class);
            var rotated=request("POST","/api/v1/admin/api-key/rotate",token,null,null); assertEquals(200,rotated.statusCode()); business=json(rotated).path("apiKey").asString();
        }
        void restart() throws Exception { context.close(); start(); }
        HttpResponse<String> request(String method,String path,String admin,String key,String body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(6)).header("X-Request-Id","forged-p803");
            if(admin!=null) builder.header("Authorization","Bearer "+admin);
            if(key!=null) builder.header("X-HookFlow-Api-Key",key);
            if(body!=null) builder.header("Content-Type","application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
        HttpResponse<String> put(String mode,int n,int delay) throws Exception {
            var response=request("PUT",CONFIG,token,null,mapper.writeValueAsString(Map.of("mode",mode,"failFirstN",n,"delayMs",delay)));
            if(response.statusCode()==200) { noSecrets(response.body()); assertEquals(4,json(response).size()); }
            return response;
        }
        JsonNode getConfig() throws Exception { var response=request("GET",CONFIG,token,null,null); assertEquals(200,response.statusCode(),response.body()); noSecrets(response.body()); return json(response); }
        JsonNode page(String query) throws Exception { var response=request("GET",REQUESTS+query,token,null,null); assertEquals(200,response.statusCode(),response.body()); noSecrets(response.body()); return json(response); }
        void assertConfig(JsonNode node,String mode,int n,int delay) { assertEquals(4,node.size()); assertEquals(mode,node.path("mode").asString()); assertEquals(n,node.path("failFirstN").asInt()); assertEquals(delay,node.path("delayMs").asInt()); assertTrue(node.path("updatedAt").asString().endsWith("Z")); }
        void noSecrets(String text) { for(String secret:List.of(encryption,token==null?"no-token-marker":token,business==null?"no-business-marker":business,"p803-admin-password","input-secret-marker","p803_reject","INSERT INTO","UPDATE simulator_config","sensitivePayloadMarker")) assertFalse(text.contains(secret),secret); }
        void assertNoAudit(HttpResponse<String> response) { assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Integer.class,response.headers().firstValue("X-Request-Id").orElseThrow())); }
        void error(HttpResponse<String> response,int status) {
            assertEquals(status,response.statusCode(),response.body()); var body=json(response); assertEquals(3,body.size());
            String requestId=response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(requestId).toString(),requestId); assertNotEquals("forged-p803",requestId); assertEquals(requestId,body.path("requestId").asString());
            assertEquals(switch(status) { case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; default -> "INTERNAL_ERROR"; },body.path("code").asString());
            noSecrets(response.body()); assertNoAudit(response);
        }
        void audit(HttpResponse<String> response) {
            assertEquals(200,response.statusCode(),response.body()); String requestId=response.headers().firstValue("X-Request-Id").orElseThrow();
            assertEquals(UUID.fromString(requestId).toString(),requestId); assertNotEquals("forged-p803",requestId);
            var audit=jdbc.queryForMap("SELECT * FROM audit_log WHERE request_id=?",requestId);
            assertEquals(actor,((Number)audit.get("actor_user_id")).longValue()); assertEquals("SIMULATOR_CONFIG_UPDATED",audit.get("action"));
            assertEquals("SIMULATOR_CONFIG",audit.get("object_type")); assertEquals("1",audit.get("object_id"));
            var now=jdbc.queryForObject("SELECT updated_at FROM simulator_config",LocalDateTime.class);
            assertEquals(now,audit.get("occurred_at")); assertEquals(now.toInstant(ZoneOffset.UTC),Instant.parse(json(response).path("updatedAt").asString())); noSecrets(audit.toString());
        }
        String event(int timeout) throws Exception {
            var ep=request("POST","/api/v1/admin/endpoints",token,null,"{\"name\":\"p803\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":"+timeout+"}"); assertEquals(201,ep.statusCode());
            var event=request("POST","/api/v1/admin/events/test",token,null,"{\"eventType\":\"order.paid\",\"endpointId\":"+json(ep).path("id").asLong()+",\"payload\":"+new String(BODY,StandardCharsets.UTF_8)+"}");
            assertEquals(202,event.statusCode()); return json(event).path("eventId").asString();
        }
        HttpResponse<String> signed(String id,boolean invalid) throws Exception { return signed(id,invalid,BODY); }
        HttpResponse<String> signed(String id,boolean invalid,byte[] body) throws Exception {
            String secret=context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(jdbc.queryForObject("SELECT secret_ciphertext_snapshot FROM delivery_task WHERE id=?",(rs,row)->rs.getBytes(1),task(id)));
            String time=clock.instant().toString(), signature=context.getBean(DeliveryRequestSigner.class).sign(id,time,body,secret);
            var response=client.send(HttpRequest.newBuilder(URI.create(base+SimulatorInternalTargetRoute.PATH)).timeout(Duration.ofSeconds(6)).header("Content-Type","application/json").header("X-Request-Id","forged-p803")
                    .header("X-HookFlow-Event-Id",id).header("X-HookFlow-Timestamp",time).header("X-HookFlow-Signature",invalid?"0".repeat(64):signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertFalse(response.body().contains(secret)); assertFalse(response.body().contains(signature)); return response;
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
        long auditCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SIMULATOR_CONFIG_UPDATED'",Long.class); }
        LocalDateTime now() { return LocalDateTime.ofInstant(clock.mutable.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC); }
        Map<String,Object> configRow() { return jdbc.queryForMap("SELECT * FROM simulator_config"); }
        Map<String,Object> state(String id) { return jdbc.queryForMap("SELECT * FROM simulator_event_state WHERE event_id=?",id); }
        List<Map<String,Object>> logs(String id) { return jdbc.queryForList("SELECT * FROM simulator_request_log WHERE event_id=? ORDER BY id",id); }
        int writes() { return context.getBean(ObservedRepository.class).writes(); }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no",Long.class,id); }
        SimulatorManagementService management() { return context.getBean(SimulatorManagementService.class); }
        SimulatorProcessingService processing() { return context.getBean(SimulatorProcessingService.class); }
        Object update(SimulatorConfiguration value) { return management().update(value,actor,UUID.randomUUID().toString()); }
        void scan() { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
        void awaitTask(long task,String status) throws Exception { await(()->status.equals(jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,task)),5000); }
        void assertAttempt(long task,int attempt,String result,Integer status) { var row=jdbc.queryForMap("SELECT * FROM delivery_attempt WHERE task_id=? AND attempt_no=?",task,attempt); assertEquals(result,row.get("result")); assertEquals(status,row.get("http_status")); assertNotNull(row.get("ended_at")); }
        void advanceToRetry(long task,int attempt,int seconds) throws Exception {
            var ended=jdbc.queryForObject("SELECT ended_at FROM delivery_attempt WHERE task_id=? AND attempt_no=?",LocalDateTime.class,task,attempt);
            var due=jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?",LocalDateTime.class,task); assertEquals(ended.plusSeconds(seconds),due);
            clock.set(due.toInstant(ZoneOffset.UTC).minusMillis(1)); scan(); assertEquals(attempt,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Integer.class,task)); clock.set(due.toInstant(ZoneOffset.UTC));
        }
        void consistent(long task,String status,int attempts) {
            var row=jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?",task); assertEquals(status,row.get("status")); assertEquals(attempts,row.get("attempt_count"));
            var event=jdbc.queryForMap("SELECT * FROM event WHERE id=?",row.get("event_pk")); assertEquals(status,event.get("current_status")); assertEquals(row.get("round_no"),event.get("latest_round_no"));
            assertEquals(row.get("finished_at"),event.get("terminal_at")); assertEquals(attempts,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Integer.class,task));
        }
        void verifyReadModel() throws Exception {
            var rows=jdbc.queryForList("SELECT id,event_id,planned_status,config_mode,duplicate_processed FROM simulator_request_log ORDER BY received_at DESC,id DESC");
            var view=page("?size=100"); assertEquals(rows.size(),view.path("total").asInt()); assertEquals(rows.size(),view.path("items").size());
            for(int i=0;i<rows.size();i++) { var row=rows.get(i); var item=view.path("items").get(i); assertEquals(((Number)row.get("id")).longValue(),item.path("id").asLong()); assertEquals(row.get("event_id"),item.path("eventId").asString()); assertEquals(((Number)row.get("planned_status")).intValue(),item.path("plannedStatus").asInt()); assertEquals(row.get("config_mode"),item.path("configMode").asString()); assertEquals(flag(row,"duplicate_processed"),item.path("duplicateProcessed").asBoolean()); }
        }
        TransactionTemplate transaction() { var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx; }
        Object ordered(String table,String lockData,Supplier<?> winner,Supplier<?> loser,Runnable beforeCommit) throws Exception {
            var ready=new CountDownLatch(1); var release=new CountDownLatch(1); var blocker=new AtomicReference<Long>(); var waiter=new AtomicReference<Long>();
            var workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
            var observer=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())); observer.setQueryTimeout(2);
            Future<?> first=workers.submit(()->transaction().execute(tx->{
                blocker.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)); var value=winner.get(); ready.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } return value;
            }));
            try {
                assertTrue(ready.await(5,TimeUnit.SECONDS)); Future<?> second=workers.submit(()->transaction().execute(tx->{waiter.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)); return loser.get();}));
                await(()->waiter.get()!=null && observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        +"JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE "
                        +"JOIN performance_schema.data_locks rt ON rt.ENGINE_TRANSACTION_ID=w.REQUESTING_ENGINE_TRANSACTION_ID AND rt.ENGINE=w.ENGINE AND rt.LOCK_TYPE='TABLE' AND rt.OBJECT_NAME=r.OBJECT_NAME "
                        +"JOIN performance_schema.data_locks bt ON bt.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID AND bt.ENGINE=w.ENGINE AND bt.LOCK_TYPE='TABLE' AND bt.OBJECT_NAME=r.OBJECT_NAME "
                        +"JOIN performance_schema.threads rthread ON rthread.THREAD_ID=rt.THREAD_ID "
                        +"JOIN performance_schema.threads bthread ON bthread.THREAD_ID=bt.THREAD_ID "
                        +"WHERE rthread.PROCESSLIST_ID=? AND bthread.PROCESSLIST_ID=? "
                        +"AND r.OBJECT_NAME=? AND r.INDEX_NAME='PRIMARY' AND r.LOCK_TYPE='RECORD' AND r.LOCK_DATA=?",
                        Long.class,waiter.get(),blocker.get(),table,lockData)>0,3500);
                assertFalse(second.isDone()); beforeCommit.run(); release.countDown(); first.get(6,TimeUnit.SECONDS); return second.get(6,TimeUnit.SECONDS);
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(8,TimeUnit.SECONDS)); }
        }
        @Override public void close() { client.close(); context.close(); }
    }
}
