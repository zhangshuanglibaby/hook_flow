package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointEnableService;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.event.application.EventQueryService;
import com.hookflow.event.application.EventQueryService.Filter;
import com.hookflow.event.infrastructure.EventQueryRepository;
import com.hookflow.support.MutableClock;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EventQueryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final String EVENTS = "/api/v1/admin/events";
    static final Instant NOW = Instant.parse("2026-10-30T03:00:00.123Z");
    static final String PAYLOAD_MARKER = "p901-fictitious-payload-never-log";
    static final Filter ALL = new Filter(null,null,null,null,null,null);

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock eventQueryClock() { return new MutableClock(NOW); }
        @Bean @Primary ObservedEvents observedEventQueryRepository(NamedParameterJdbcTemplate jdbc) { return new ObservedEvents(jdbc); }
    }
    static class ObservedEvents extends EventQueryRepository {
        final AtomicReference<Runnable> afterCount = new AtomicReference<>(), afterFind = new AtomicReference<>();
        ObservedEvents(NamedParameterJdbcTemplate jdbc) { super(jdbc); }
        @Override public long count(Filter filter) { long total=super.count(filter); run(afterCount); return total; }
        @Override public Optional<StoredDetail> find(String id) { var found=super.find(id); run(afterFind); return found; }
        AtomicReference<Runnable> countHook() { return afterCount; }
        AtomicReference<Runnable> findHook() { return afterFind; }
        private static void run(AtomicReference<Runnable> slot) { var hook=slot.getAndSet(null); if(hook!=null) hook.run(); }
    }

    @AfterEach void clear() {
        var jdbc=independent();
        for(String table:List.of("simulator_request_log","simulator_event_state","simulator_config",
                "delivery_attempt","delivery_task","event","audit_log","endpoint")) jdbc.update("DELETE FROM "+table);
    }

    @Test void emptyDatabaseDefaultsEmptyPagesAndAuthorizationUseFormalHttpChain() throws Exception {
        try(var f=new Fixture()) {
            var empty=f.page(""); assertEquals(4,empty.size()); assertEquals(1,empty.path("page").asInt());
            assertEquals(20,empty.path("size").asInt()); assertEquals(0,empty.path("total").asLong()); assertEquals(0,empty.path("items").size());
            for(String path:List.of(EVENTS,EVENTS+"/missing-id")) {
                f.error(f.request("GET",path,null,null,null),401);
                f.error(f.request("GET",path,null,f.business,null),403);
            }
            f.error(f.get(EVENTS+"/missing-id"),404);
            for(String query:List.of("?eventId=missing","?endpointId=9223372036854775807","?eventType=unknown")) {
                var page=f.page(query); assertEquals(0,page.path("total").asLong()); assertEquals(0,page.path("items").size());
            }
            assertEquals(1,f.count("audit_log")); assertEquals(0,f.count("event"));
        }
    }

    @Test void formalHttpRejectsInvalidPagingStatusesIdsTimesAndPathsWithSafeRequestIds() throws Exception {
        try(var f=new Fixture()) {
            for(String query:List.of("page=0","page=-1","page=1.0","page=2147483648","page=9223372036854775808","page=",
                    "size=0","size=101","size=2147483648","size=1.0","size=", "status=success","status=UNKNOWN","status=",
                    "endpointId=0","endpointId=-1","endpointId=1.0","endpointId=9223372036854775808","endpointId=",
                    "eventId=","eventType=", "eventId="+enc(" "),"eventType="+enc("\t"),
                    "eventId="+enc("😀".repeat(129)),"eventType="+enc("a".repeat(129)),
                    "from=2026-10-30","to=bad","from="+enc("2026-10-30T08:00:00+08:00"),
                    "from=2026-10-30T00:00:00Z&to=2026-10-30T00:00:00Z",
                    "from=2026-10-30T00:00:01Z&to=2026-10-30T00:00:00Z")) f.error(f.get(EVENTS+"?"+query),400);
            f.error(f.get(EVENTS+"/"+enc(" ")),400);
            f.error(f.get(EVENTS+"/"+enc("😀".repeat(129))),400);
            f.error(f.get(EVENTS+"/"+enc("😀".repeat(128))),404);
            f.error(f.get(EVENTS+"/not-a-uuid-or-number"),404);
            assertEquals(1,f.count("audit_log"));
        }
    }

    @Test void equalCreationTimesHaveStableIdDescendingPagesAndRealSqlLimits() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000);
            for(int i=0;i<25;i++) f.event("stable-"+i,"order.paid",endpoint,"{}");
            var expected=f.jdbc.queryForList("SELECT event_id FROM event ORDER BY created_at DESC,id DESC",String.class);
            var first=f.page(""); var second=f.page("?page=2");
            assertEquals(25,first.path("total").asLong()); assertEquals(20,first.path("items").size()); assertEquals(5,second.path("items").size());
            var actual=new ArrayList<String>(); first.path("items").forEach(n->actual.add(n.path("eventId").asString()));
            second.path("items").forEach(n->actual.add(n.path("eventId").asString())); assertEquals(expected,actual);
            for(var item:first.path("items")) { assertEquals(6,item.size()); assertFalse(item.has("payload")); assertEquals(NOW,Instant.parse(item.path("createdAt").asString())); }
            for(String query:List.of("?page=3","?page=2147483647&size=100")) {
                var empty=f.page(query); assertEquals(25,empty.path("total").asLong()); assertEquals(0,empty.path("items").size());
            }
            assertEquals(1,f.page("?size=1").path("items").size()); assertEquals(25,f.page("?size=100").path("items").size());
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM event",Integer.class));
        }
    }

    @Test void sixLatestStatusesAndEveryFilterAndCombinationUseEventCreationTimeAndExactIdentity() throws Exception {
        try(var f=new Fixture()) {
            long a=f.endpoint(5000), b=f.endpoint(5000);
            f.event("pending","Type.A",a,"{}");
            f.event("delivering","Type.A",a,"{}"); f.claim("delivering");
            f.event("retry","Type.A",a,"{}"); f.finish("retry",SingleDeliveryHttpResult.fromHttpResponse(500,"failure"));
            f.event("success","Type.B",a,"{}"); f.finish("success",SingleDeliveryHttpResult.fromHttpResponse(204,""));
            f.event("final","Type.A",a,"{}"); f.finish("final",SingleDeliveryHttpResult.fromHttpResponse(404,"not found"));
            f.event("paused","Type.A",b,"{}"); f.disable(b);
            for(String state:List.of("PENDING","DELIVERING","WAIT_RETRY","SUCCESS","FINAL_FAILED","PAUSED")) {
                var page=f.page("?status="+state); assertEquals(1,page.path("total").asInt()); assertEquals(state,page.path("items").get(0).path("status").asString());
            }
            assertEquals(5,f.page("?endpointId="+a).path("total").asInt()); assertEquals(5,f.page("?eventType=Type.A").path("total").asInt());
            var combined=f.page("?eventId=retry&eventType=Type.A&status=WAIT_RETRY&endpointId="+a+"&from="+NOW+"&to="+NOW.plusMillis(1));
            assertEquals(1,combined.path("total").asInt()); assertEquals("retry",combined.path("items").get(0).path("eventId").asString());
            assertEquals(0,f.page("?eventId=retry&eventType=Type.B").path("total").asInt());
            assertEquals(0,f.page("?eventId=success&status=FINAL_FAILED").path("total").asInt());
            assertEquals(0,f.page("?eventId=retry&endpointId="+b).path("total").asInt());
            f.clock.setInstant(NOW.plusMillis(1)); f.event("at-upper","boundary",a,"{}");
            f.clock.setInstant(NOW.minusMillis(1)); f.event("before-lower","boundary",a,"{}");
            assertEquals(6,f.page("?from="+NOW+"&to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(7,f.page("?from="+NOW).path("total").asInt()); assertEquals(7,f.page("?to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(0,f.page("?from=2026-10-30T03:00:00.123000001Z&to=2026-10-30T03:00:00.123999999Z").path("total").asInt());
            assertEquals(1,f.page("?eventType=boundary&from=2026-10-30T03:00:00.123000001Z").path("total").asInt());
            assertEquals(1,f.page("?eventType=boundary&to=2026-10-30T03:00:00.123000001Z").path("total").asInt());
        }
    }

    @Test void caseSpacesUnicodeAndSqlWildcardsAreLiteralForListAndPath() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000);
            for(String id:List.of("Case","case"," Case ","é","é","😀".repeat(128),"literal%_","literalZZ","a' OR 1=1 --")) {
                f.event(id,id,endpoint,"{}");
            }
            for(String id:List.of("Case","case"," Case ","é","é","😀".repeat(128),"literal%_","a' OR 1=1 --")) {
                var page=f.page("?eventId="+enc(id)+"&eventType="+enc(id)); assertEquals(1,page.path("total").asInt());
                assertEquals(id,page.path("items").get(0).path("eventId").asString());
                if(id.contains("%")) {
                    // 默认容器/安全链前置拒绝 %25 路径；不为查询放宽全局防火墙。
                    f.error(f.get(EVENTS+"/"+enc(id)),401);
                    assertEquals(id,f.context.getBean(EventQueryService.class).detail(id).eventId());
                } else assertEquals(id,f.detail(id).path("eventId").asString());
            }
            assertEquals(0,f.page("?eventType=CASE").path("total").asInt());
            assertEquals(0,f.page("?eventId=%25").path("total").asInt());
        }
    }

    @Test void everyPayloadJsonTypeAndExactLargeNumbersRemainJsonValuesThroughFormalGet() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000);
            for(String payload:List.of("null","true","false","\"text 中文\"","[3,{\"b\":2,\"a\":1},null]",
                    "900719925474099312345678901234567890", "0.123456789012345678901234567890123456789",
                    "{\"z\":1.0,\"a\":[false,null,123456789012345678901234567890]}")) {
                String id="json-"+UUID.randomUUID(); f.event(id,"json",endpoint,payload);
                var detail=f.detail(id); var canonicalizer=new com.hookflow.event.domain.EventPayloadCanonicalizer();
                assertEquals(canonicalizer.canonicalize(f.precise.readTree(payload)).json(),canonicalizer.canonicalize(detail.path("payload")).json());
                assertEquals(8,detail.size()); assertEquals(1,detail.path("rounds").size()); assertEquals(0,detail.path("rounds").get(0).path("attempts").size());
                assertEquals(9,detail.path("rounds").get(0).size());
                assertEquals(f.precise.readTree(f.jdbc.queryForObject("SELECT payload_json FROM event WHERE event_id=?",String.class,id)),detail.path("payload"));
            }
        }
    }

    @Test void completeHistoryPreservesAllResultsNullableStartedFieldsOrderAndStoredExcerptBoundary() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("history","history",endpoint,"{\"marker\":\""+PAYLOAD_MARKER+"\"}");
            f.finish("history",SingleDeliveryHttpResult.fromHttpResponse(500,"中".repeat(1365)+"x"));
            f.due("history"); f.finish("history",SingleDeliveryHttpResult.unknown());
            f.due("history"); f.finish("history",SingleDeliveryHttpResult.localFailure());
            f.clock.advance(Duration.ofSeconds(2)); f.redeliver("history"); f.finish("history",SingleDeliveryHttpResult.fromHttpResponse(404,"old-round"));
            f.redeliver("history"); f.claim("history");
            f.event("successful-history","history",endpoint,"{}"); f.finish("successful-history",SingleDeliveryHttpResult.fromHttpResponse(200,"ok"));
            assertEquals("SUCCESS",f.detail("successful-history").path("rounds").get(0).path("attempts").get(0).path("result").asString());
            var before=f.snapshot(); var detail=f.detail("history"); assertEquals(before,f.snapshot());
            assertEquals("DELIVERING",detail.path("status").asString()); assertEquals(3,detail.path("latestRoundNo").asInt());
            var rounds=detail.path("rounds"); assertEquals(3,rounds.size());
            for(int i=0;i<3;i++) { assertEquals(i+1,rounds.get(i).path("roundNo").asInt()); assertEquals(9,rounds.get(i).size()); }
            var attempts=rounds.get(0).path("attempts"); assertEquals(3,attempts.size());
            assertEquals(List.of("HTTP_FAILURE","UNKNOWN","LOCAL_FAILURE"),List.of(attempts.get(0).path("result").asString(),attempts.get(1).path("result").asString(),attempts.get(2).path("result").asString()));
            for(int i=0;i<3;i++) { assertEquals(i+1,attempts.get(i).path("attemptNo").asInt()); assertEquals(9,attempts.get(i).size()); }
            assertEquals(4096,attempts.get(0).path("responseExcerpt").asString().getBytes(StandardCharsets.UTF_8).length);
            assertEquals("HTTP 结果未知",attempts.get(1).path("errorMessage").asString()); assertTrue(attempts.get(1).path("httpStatus").isNull());
            var started=rounds.get(2).path("attempts").get(0); assertEquals("STARTED",started.path("result").asString());
            for(String name:List.of("endedAt","durationMs","httpStatus","responseExcerpt","errorCode","errorMessage")) assertTrue(started.path(name).isNull(),name);
            assertTrue(rounds.get(2).path("finishedAt").isNull()); assertTrue(rounds.get(2).path("nextRunAt").isNull());
            assertEquals(NOW,Instant.parse(detail.path("createdAt").asString()));
        }
    }

    @Test void formalAdministratorUuidRealSimulatorDeliveryAndRedeliveryCanBeQueriedWithoutDuplicateEvents() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.mode("ALWAYS_500");
            var created=f.request("POST",EVENTS+"/test",f.token,null,"{\"eventType\":\"formal\",\"endpointId\":"+endpoint+",\"payload\":{\"a\":1}}");
            assertEquals(202,created.statusCode()); String id=f.json(created).path("eventId").asString(); UUID.fromString(id);
            for(int attempt=1;attempt<=3;attempt++) {
                f.scan(); f.awaitStatus(id,attempt==3?"FINAL_FAILED":"WAIT_RETRY"); if(attempt<3) f.due(id);
            }
            var old=f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no",f.task(id));
            assertEquals(3,old.size()); var failed=f.detail(id); assertEquals(3,failed.path("rounds").get(0).path("attempts").size());
            f.clock.advance(Duration.ofMinutes(2)); f.mode("ALWAYS_200");
            var redelivery=f.request("POST",EVENTS+"/"+enc(id)+"/redeliver",f.token,null,null); assertEquals(202,redelivery.statusCode());
            var pending=f.detail(id); assertEquals(2,pending.path("latestRoundNo").asInt()); assertEquals("PENDING",pending.path("status").asString());
            assertEquals(2,pending.path("rounds").size()); assertEquals(0,pending.path("rounds").get(1).path("attempts").size());
            f.scan(); f.awaitStatus(id,"SUCCESS"); var finalView=f.detail(id);
            assertEquals("SUCCESS",finalView.path("status").asString()); assertEquals(200,finalView.path("rounds").get(1).path("attempts").get(0).path("httpStatus").asInt());
            assertEquals("HTTP_FAILURE",finalView.path("rounds").get(0).path("attempts").get(0).path("result").asString());
            assertEquals(old,f.jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no",pending.path("rounds").get(0).path("taskId").asLong()));
            assertEquals(1,f.page("?eventId="+id+"&status=SUCCESS&from="+NOW+"&to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(0,f.page("?eventId="+id+"&status=FINAL_FAILED").path("total").asInt());
            assertEquals(0,f.page("?from="+NOW.plusSeconds(3600)).path("total").asInt());
            assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM simulator_request_log WHERE event_id=?",Integer.class,id));
            assertEquals(1,f.count("event"));
        }
    }

    @Test void successfulQueriesDoNotWriteBusinessOrAuditDataAndNeverLogPayloadOrStoredCredentials() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("safe-read","safe",endpoint,"{\"marker\":\""+PAYLOAD_MARKER+"\"}");
            var claim=f.claim("safe-read"); f.secrets.add(claim.claimToken());
            byte[] cipher=f.jdbc.queryForObject("SELECT secret_ciphertext_snapshot FROM delivery_task WHERE id=?",(rs,row)->rs.getBytes(1),claim.taskId());
            f.secrets.add(Base64.getEncoder().encodeToString(cipher));
            var logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var logs=new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                var before=f.snapshot();
                f.page(""); f.page("?eventId=safe-read&status=DELIVERING");
                var detail=f.detail("safe-read"); assertEquals(PAYLOAD_MARKER,detail.path("payload").path("marker").asString());
                assertEquals(before,f.snapshot());
                for(var log:logs.list) { f.noSecrets(log.getFormattedMessage()); assertFalse(log.getFormattedMessage().contains(PAYLOAD_MARKER)); assertNull(log.getThrowableProxy()); }
            } finally { logger.detachAppender(logs); logs.stop(); }
        }
    }

    @Test void formalDelayedProcessedResponseStillShowsActualUnknownAttemptRatherThanPlanned200() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(1200); String id="actual-unknown";
            assertEquals(200,f.request("PUT","/api/v1/admin/simulator/config",f.token,null,
                    "{\"mode\":\"PROCESSED_THEN_DELAY\",\"delayMs\":3500}").statusCode());
            f.event(id,"timeout",endpoint,"{\"marker\":\""+PAYLOAD_MARKER+"\"}"); f.scan(); f.awaitStatus(id,"WAIT_RETRY");
            var first=f.detail(id).path("rounds").get(0).path("attempts").get(0);
            assertEquals("UNKNOWN",first.path("result").asString()); assertTrue(first.path("httpStatus").isNull());
            assertTrue(first.path("responseExcerpt").isNull()); assertEquals("HTTP 结果未知",first.path("errorMessage").asString());
            assertEquals(200,f.jdbc.queryForObject("SELECT planned_status FROM simulator_request_log WHERE event_id=?",Integer.class,id));
            assertNotNull(f.jdbc.queryForObject("SELECT processed_at FROM simulator_event_state WHERE event_id=?",LocalDateTime.class,id));
            f.due(id); f.scan(); f.awaitStatus(id,"SUCCESS");
            var history=f.detail(id).path("rounds").get(0).path("attempts"); assertEquals(2,history.size());
            assertEquals(first,history.get(0)); assertEquals("SUCCESS",history.get(1).path("result").asString());
            assertEquals(200,history.get(1).path("httpStatus").asInt());
        }
    }

    @Test void readFailuresAreSafe500AndDoNotPretendToBeEmptyOrLeakLogsPayloadOrCredentials() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("read-failure","safe",endpoint,"{\"marker\":\""+PAYLOAD_MARKER+"\"}");
            var logger=(Logger)LoggerFactory.getLogger(com.hookflow.common.api.ApiExceptionHandler.class);
            var logs=new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                var snapshot=f.snapshot();
                for(String table:List.of("event","delivery_task","delivery_attempt")) {
                    f.jdbc.execute("RENAME TABLE "+table+" TO p901_unavailable");
                    try {
                        if(table.equals("event")) f.error(f.get(EVENTS),500);
                        f.error(f.get(EVENTS+"/read-failure"),500);
                    } finally { f.jdbc.execute("RENAME TABLE p901_unavailable TO "+table); }
                }
                assertEquals(snapshot,f.snapshot()); f.page(""); f.detail("read-failure");
                assertFalse(logs.list.isEmpty());
                for(var log:logs.list) { f.noSecrets(log.getFormattedMessage()); assertFalse(log.getFormattedMessage().contains(PAYLOAD_MARKER)); assertNull(log.getThrowableProxy()); assertNotNull(log.getMDCPropertyMap().get("requestId")); }
            } finally { logger.detachAppender(logs); logs.stop(); }
        }
    }

    @Test void listCountAndItemsShareSnapshotWhenCreationOrStateChangeCommitsBetweenQueries() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("snapshot","snapshot",endpoint,"{}");
            var observed=f.context.getBean(ObservedEvents.class);
            var first=f.between(observed.countHook(),()->f.page(""),()->f.event("newly-committed","snapshot",endpoint,"{}"));
            assertEquals(1,first.path("total").asInt()); assertEquals(1,first.path("items").size()); assertEquals("snapshot",first.path("items").get(0).path("eventId").asString());
            var second=f.between(observed.countHook(),()->f.page("?eventId=snapshot&status=PENDING"),()->f.claim("snapshot"));
            assertEquals(1,second.path("total").asInt()); assertEquals("PENDING",second.path("items").get(0).path("status").asString());
            assertEquals(0,f.page("?eventId=snapshot&status=PENDING").path("total").asInt());
        }
    }

    @Test void detailSnapshotDoesNotMixNewRoundOrCompletedAttemptWithPreviouslyReadEvent() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("snapshot-history","snapshot",endpoint,"{}");
            var claimed=f.claim("snapshot-history"); var observed=f.context.getBean(ObservedEvents.class);
            var beforeCompletion=f.between(observed.findHook(),()->f.read(EVENTS+"/snapshot-history"),()->f.complete(claimed,SingleDeliveryHttpResult.fromHttpResponse(404,"old")));
            assertEquals("DELIVERING",beforeCompletion.path("status").asString()); assertEquals("STARTED",beforeCompletion.path("rounds").get(0).path("attempts").get(0).path("result").asString());
            var beforeRedelivery=f.between(observed.findHook(),()->f.read(EVENTS+"/snapshot-history"),()->f.redeliver("snapshot-history"));
            assertEquals(1,beforeRedelivery.path("latestRoundNo").asInt()); assertEquals(1,beforeRedelivery.path("rounds").size()); assertEquals("FINAL_FAILED",beforeRedelivery.path("status").asString());
            var after=f.detail("snapshot-history"); assertEquals(2,after.path("latestRoundNo").asInt()); assertEquals(2,after.path("rounds").size());
        }
    }

    @Test void readsReturnCommittedSnapshotBeforeWinningBusinessWriteTransactionsReleaseTheirRealLocks() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("uncommitted","locks",endpoint,"{}");
            var claimed=new AtomicReference<DeliveryClaimService.ClaimedTask>();
            f.whileUncommitted("uncommitted",()->claimed.set(f.claim("uncommitted")),"PENDING",1,"none");
            f.whileUncommitted("uncommitted",()->f.complete(claimed.get(),SingleDeliveryHttpResult.fromHttpResponse(500,"retry")),"DELIVERING",1,"STARTED");
            f.whileUncommitted("uncommitted",()->f.disable(endpoint),"WAIT_RETRY",1,"HTTP_FAILURE");
            f.whileUncommitted("uncommitted",()->f.enable(endpoint),"PAUSED",1,"HTTP_FAILURE");
            f.due("uncommitted"); claimed.set(f.claim("uncommitted")); f.clock.setInstant(claimed.get().leaseUntil().toInstant(ZoneOffset.UTC));
            f.whileUncommitted("uncommitted",()->assertTrue(f.context.getBean(DeliveryRecoveryService.class).recover(f.task("uncommitted"),claimed.get().claimToken())),"DELIVERING",1,"STARTED");
            f.due("uncommitted"); f.finish("uncommitted",SingleDeliveryHttpResult.fromHttpResponse(404,"failed"));
            f.whileUncommitted("uncommitted",()->f.redeliver("uncommitted"),"FINAL_FAILED",1,"HTTP_FAILURE");
            assertEquals(2,f.detail("uncommitted").path("latestRoundNo").asInt());
        }
    }

    static String enc(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    static JdbcTemplate independent() { var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())); jdbc.setQueryTimeout(3); return jdbc; }
    static void await(Supplier<Boolean> condition) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition.get()) { if(System.nanoTime()>=end) fail("有界等待超时"); Thread.sleep(10); } }
    static void waitLatch(CountDownLatch latch) { try { assertTrue(latch.await(4,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }

    static class Fixture implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final JsonMapper precise=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        final ConfigurableApplicationContext context;
        final JdbcTemplate jdbc;
        final MutableClock clock;
        final String base,token,business,encryption;
        final long actor;
        final List<String> secrets=new ArrayList<>();
        Fixture() throws Exception {
            byte[] key=new byte[32]; new SecureRandom().nextBytes(key); encryption=Base64.getEncoder().encodeToString(key);
            context=new SpringApplicationBuilder(HookflowBackendApplication.class,Configuration.class).run(
                    "--spring.profiles.active=p2-login-test","--server.port=0","--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL="+MYSQL.getJdbcUrl(),"--DB_USERNAME="+MYSQL.getUsername(),"--DB_PASSWORD="+MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p901-admin","--HOOKFLOW_ADMIN_PASSWORD=p901-admin-password","--HOOKFLOW_SECRET_ENCRYPTION_KEY="+encryption,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc=context.getBean(JdbcTemplate.class); clock=context.getBean(MutableClock.class);
            base="http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port");
            var login=request("POST","/api/v1/auth/login",null,null,"{\"username\":\"p901-admin\",\"password\":\"p901-admin-password\"}"); assertEquals(200,login.statusCode()); token=json(login).path("token").asString();
            var rotate=request("POST","/api/v1/admin/api-key/rotate",token,null,null); assertEquals(200,rotate.statusCode()); business=json(rotate).path("apiKey").asString();
            secrets.addAll(List.of(token,business,encryption,"p901-admin-password")); actor=jdbc.queryForObject("SELECT id FROM app_user WHERE username='p901-admin'",Long.class);
        }
        HttpResponse<String> request(String method,String path,String admin,String key,String body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(7)).header("X-Request-Id","forged-p901");
            if(admin!=null) builder.header("Authorization","Bearer "+admin); if(key!=null) builder.header("X-HookFlow-Api-Key",key);
            if(body!=null) builder.header("Content-Type","application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET",path,token,null,null); }
        JsonNode json(HttpResponse<String> response) { return precise.readTree(response.body()); }
        JsonNode page(String query) throws Exception { return read(EVENTS+query); }
        JsonNode detail(String id) throws Exception {
            var detail=read(EVENTS+"/"+enc(id));
            var tasks=jdbc.queryForList("SELECT t.* FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? ORDER BY t.round_no",id);
            assertEquals(tasks.size(),detail.path("rounds").size());
            for(int i=0;i<tasks.size();i++) {
                var task=tasks.get(i); var round=detail.path("rounds").get(i);
                assertEquals(((Number)task.get("id")).longValue(),round.path("taskId").asLong());
                assertEquals(task.get("status"),round.path("status").asString());
                assertEquals(((Number)task.get("attempt_count")).intValue(),round.path("attemptCount").asInt());
                assertEquals(((Number)task.get("max_attempts")).intValue(),round.path("maxAttempts").asInt());
                time(task.get("created_at"),round.path("createdAt")); time(task.get("next_run_at"),round.path("nextRunAt")); time(task.get("finished_at"),round.path("finishedAt"));
                var attempts=jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no",task.get("id"));
                assertEquals(attempts.size(),round.path("attempts").size());
                for(int j=0;j<attempts.size();j++) {
                    var stored=attempts.get(j); var attempt=round.path("attempts").get(j);
                    assertEquals(9,attempt.size()); assertEquals(stored.get("result"),attempt.path("result").asString());
                    assertEquals(j+1,attempt.path("attemptNo").asInt()); time(stored.get("started_at"),attempt.path("startedAt")); time(stored.get("ended_at"),attempt.path("endedAt"));
                    for(String[] field:List.of(new String[]{"duration_ms","durationMs"},new String[]{"http_status","httpStatus"},new String[]{"response_excerpt","responseExcerpt"},new String[]{"error_code","errorCode"},new String[]{"error_message","errorMessage"})) {
                        var value=stored.get(field[0]); var node=attempt.path(field[1]);
                        if(value==null) assertTrue(node.isNull(),field[1]); else if(value instanceof Number number) assertEquals(number.longValue(),node.asLong()); else assertEquals(value,node.asString());
                    }
                }
            }
            return detail;
        }
        void time(Object stored,JsonNode value) {
            if(stored==null) assertTrue(value.isNull());
            else { assertTrue(value.asString().endsWith("Z")); assertEquals(((LocalDateTime)stored).toInstant(ZoneOffset.UTC),Instant.parse(value.asString())); }
        }
        JsonNode read(String path) throws Exception { var response=get(path); assertEquals(200,response.statusCode(),path+" "+response.body()); noSecrets(response.body()); noAudit(response); return json(response); }
        void noAudit(HttpResponse<String> response) { String id=response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(id).toString(),id); assertNotEquals("forged-p901",id); assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Integer.class,id)); }
        void noSecrets(String text) {
            for(String secret:secrets) assertFalse(text.contains(secret));
            for(String name:List.of("secretCiphertext","secret_ciphertext","claimToken","claim_token","signingSecret","token_hash","key_hash","p901_unavailable","SELECT ","BadSqlGrammarException:")) assertFalse(text.contains(name),name);
        }
        void error(HttpResponse<String> response,int status) {
            assertEquals(status,response.statusCode(),response.body()); var error=json(response); assertEquals(3,error.size());
            assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),error.path("requestId").asString()); noAudit(response); noSecrets(response.body());
            assertFalse(response.body().contains(PAYLOAD_MARKER));
            assertEquals(switch(status) { case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; default -> "INTERNAL_ERROR"; },error.path("code").asString());
        }
        long endpoint(int timeout) throws Exception {
            var response=request("POST","/api/v1/admin/endpoints",token,null,"{\"name\":\"p901\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":"+timeout+"}"); assertEquals(201,response.statusCode());
            long id=json(response).path("id").asLong(); byte[] ciphertext=jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?",(rs,row)->rs.getBytes(1),id);
            secrets.add(context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(ciphertext)); secrets.add(Base64.getEncoder().encodeToString(ciphertext)); return id;
        }
        void event(String id,String type,long endpoint,String payload) throws Exception {
            String body="{\"eventId\":"+precise.writeValueAsString(id)+",\"eventType\":"+precise.writeValueAsString(type)+",\"endpointId\":"+endpoint+",\"payload\":"+payload+"}";
            var response=request("POST","/api/v1/events",null,business,body); assertEquals(202,response.statusCode(),response.body());
        }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no",Long.class,id); }
        DeliveryClaimService.ClaimedTask claim(String id) { return context.getBean(DeliveryClaimService.class).claim(task(id)).orElseThrow(); }
        void complete(DeliveryClaimService.ClaimedTask claim,SingleDeliveryHttpResult result) { assertTrue(context.getBean(DeliveryCompletionService.class).complete(claim.taskId(),claim.attemptNo(),claim.claimToken(),result)); }
        void finish(String id,SingleDeliveryHttpResult result) { complete(claim(id),result); }
        void due(String id) { clock.setInstant(jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?",LocalDateTime.class,task(id)).toInstant(ZoneOffset.UTC)); }
        void disable(long endpoint) { context.getBean(EndpointDisableService.class).disable(endpoint,actor,UUID.randomUUID().toString()); }
        void enable(long endpoint) { context.getBean(EndpointEnableService.class).enable(endpoint,actor,UUID.randomUUID().toString()); }
        void redeliver(String id) { context.getBean(DeliveryRedeliveryService.class).redeliver(id,actor,UUID.randomUUID().toString()); }
        void mode(String mode) throws Exception { assertEquals(200,request("PUT","/api/v1/admin/simulator/config",token,null,"{\"mode\":\""+mode+"\"}").statusCode()); }
        void scan() { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
        void awaitStatus(String id,String status) throws Exception { await(()->status.equals(jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?",String.class,id))); }
        LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
        Map<String,Object> snapshot() { var state=new LinkedHashMap<String,Object>(); for(String table:List.of("event","delivery_task","delivery_attempt","endpoint","audit_log")) state.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id").stream().map(row->{ var copy=new LinkedHashMap<>(row); copy.replaceAll((key,value)->value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value); return copy; }).toList()); return state; }
        TransactionTemplate tx() { var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx; }
        interface CheckedRead { JsonNode get() throws Exception; }
        interface CheckedWrite { void run() throws Exception; }
        JsonNode between(AtomicReference<Runnable> slot,CheckedRead read,CheckedWrite write) throws Exception {
            var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
            slot.set(()->{ assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class)); reached.countDown(); waitLatch(release); });
            var worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1)); Future<JsonNode> reading=worker.submit(read::get);
            try { assertTrue(reached.await(4,TimeUnit.SECONDS)); write.run(); release.countDown(); return reading.get(5,TimeUnit.SECONDS); }
            finally { release.countDown(); slot.set(null); worker.shutdownNow(); assertTrue(worker.awaitTermination(8,TimeUnit.SECONDS)); }
        }
        void whileUncommitted(String id,Runnable write,String expected,int round,String lastResult) throws Exception {
            var worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
            try {
                tx().executeWithoutResult(status->{
                    write.run(); long connection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                    var observer=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())); observer.setQueryTimeout(2);
                    assertTrue(observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_NAME='event' AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED'",Long.class,connection)>0);
                    Future<?> read=worker.submit(()->{
                        try {
                            var page=page("?eventId="+enc(id)); assertEquals(1,page.path("total").asInt()); assertEquals(expected,page.path("items").get(0).path("status").asString());
                            var detail=detail(id); assertEquals(expected,detail.path("status").asString()); assertEquals(round,detail.path("latestRoundNo").asInt()); assertEquals(round,detail.path("rounds").size());
                            var attempts=detail.path("rounds").get(round-1).path("attempts");
                            if(lastResult.equals("none")) assertEquals(0,attempts.size()); else assertEquals(lastResult,attempts.get(attempts.size()-1).path("result").asString());
                        } catch(Exception e) { throw new IllegalStateException(e); }
                    });
                    try { read.get(4,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException(e); }
                    assertFalse(status.isCompleted());
                });
            } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(8,TimeUnit.SECONDS)); }
        }
        @Override public void close() { client.close(); context.close(); }
    }
}
