package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.*;
import com.hookflow.delivery.application.DeliveryHistoryQueryService.StoredTask;
import com.hookflow.delivery.infrastructure.*;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointEnableService;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class DeliveryQueryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final String DELIVERIES = "/api/v1/admin/deliveries";
    static final Instant NOW = Instant.parse("2026-10-30T03:40:00.123Z");
    static final Set<String> SUMMARY_FIELDS = Set.of("taskId","eventId","eventType","endpointId","roundNo","status","attemptCount","maxAttempts","lastAttemptAt","createdAt");
    static final Set<String> DETAIL_FIELDS = Set.of("taskId","eventId","roundNo","status","attemptCount","maxAttempts","nextRunAt","createdAt","finishedAt","attempts");
    static final Set<String> ATTEMPT_FIELDS = Set.of("attemptNo","result","startedAt","endedAt","durationMs","httpStatus","responseExcerpt","errorCode","errorMessage");
    static final String PAYLOAD = "p902-payload-never-return-or-log";
    static final String EXCERPT = "p902-stored-excerpt-never-log";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock deliveryQueryClock() { return new MutableClock(NOW); }
        @Bean @Primary ObservedTasks observedDeliveryQueryRepository(NamedParameterJdbcTemplate jdbc) { return new ObservedTasks(jdbc); }
        @Bean @Primary ObservedHistory observedDeliveryHistoryRepository(JdbcTemplate jdbc) { return new ObservedHistory(jdbc); }
    }
    static class ObservedTasks extends DeliveryQueryRepository {
        private final AtomicReference<Runnable> countHook = new AtomicReference<>();
        ObservedTasks(NamedParameterJdbcTemplate jdbc) { super(jdbc); }
        @Override public long count(LatestTasks selection) { long total=super.count(selection); run(countHook); return total; }
        AtomicReference<Runnable> countHook() { return countHook; }
    }
    static class ObservedHistory extends DeliveryHistoryRepository {
        private final AtomicReference<Runnable> taskHook = new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
        ObservedHistory(JdbcTemplate jdbc) { super(jdbc); }
        @Override public List<DeliveryHistoryQueryService.Round> rounds(long eventPk) { rounds.incrementAndGet(); return super.rounds(eventPk); }
        int roundCalls() { return rounds.get(); }
        @Override public Optional<StoredTask> task(long id) { var found=super.task(id); run(taskHook); return found; }
        AtomicReference<Runnable> taskHook() { return taskHook; }
    }
    static void run(AtomicReference<Runnable> slot) { var hook=slot.getAndSet(null); if(hook!=null) hook.run(); }

    @AfterEach void clear() {
        var jdbc=independent();
        for(String table:List.of("simulator_request_log","simulator_event_state","simulator_config","delivery_attempt","delivery_task","event","audit_log","endpoint"))
            jdbc.update("DELETE FROM "+table);
    }

    @Test void crossModuleHistoryAndEventSelectionRequireCallerTransactionAndSingleTaskDoesNotLoadAllRounds() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("bounded-history","history",endpoint);
            var history=f.context.getBean(DeliveryHistoryQueryService.class);
            var events=f.context.getBean(com.hookflow.event.application.EventDeliveryQueryAccess.class);
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->history.task(f.task("bounded-history")));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->history.attempts(f.task("bounded-history")));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->events.select(null,null,null,null));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->events.eventId(1));
            var observed=f.context.getBean(ObservedHistory.class); int calls=observed.roundCalls();
            for(int i=0;i<4;i++) { f.finish("bounded-history",SingleDeliveryHttpResult.fromHttpResponse(404,"old")); f.redeliver("bounded-history"); }
            f.page(""); f.detail(f.task("bounded-history"));
            assertEquals(calls,observed.roundCalls()); assertEquals(5,f.count("delivery_task"));
        }
    }

    @Test void emptyDatabaseAuthorizationAndNotFoundUseFormalLoginAndRotatedBusinessKey() throws Exception {
        try(var f=new Fixture()) {
            var empty=f.page(""); assertEquals(Set.of("page","size","total","items"),empty.propertyNames());
            assertEquals(1,empty.path("page").asInt()); assertEquals(20,empty.path("size").asInt());
            assertEquals(0,empty.path("total").asLong()); assertEquals(0,empty.path("items").size());
            for(String path:List.of(DELIVERIES,DELIVERIES+"/1")) {
                f.error(f.request("GET",path,null,null,null),401);
                f.error(f.request("GET",path,null,f.business,null),403);
            }
            f.error(f.get(DELIVERIES+"/9223372036854775807"),404);
            for(String query:List.of("?eventType=missing","?endpointId=9223372036854775807","?status=SUCCESS")) {
                var page=f.page(query); assertEquals(0,page.path("total").asLong()); assertEquals(0,page.path("items").size());
            }
            assertEquals(1,f.count("audit_log"));
        }
    }

    @Test void invalidInputIs400IncludingTaskIdLongOverflowAndStrictUtcIntervals() throws Exception {
        try(var f=new Fixture()) {
            for(String query:List.of("page=0","page=-1","page=1.0","page=2147483648","page=9223372036854775808","page=",
                    "size=0","size=101","size=1.0","size=2147483648","size=", "status=success","status=UNKNOWN","status=",
                    "endpointId=0","endpointId=-1","endpointId=1.0","endpointId=9223372036854775808","endpointId=",
                    "eventType=","eventType="+enc(" \t"),"eventType="+enc("😀".repeat(129)),
                    "from=2026-10-30","to=bad","from="+enc("2026-10-30T08:00:00+08:00"),
                    "from=2026-10-30T00:00:00Z&to=2026-10-30T00:00:00Z",
                    "from=2026-10-30T00:00:01Z&to=2026-10-30T00:00:00Z")) f.error(f.get(DELIVERIES+"?"+query),400);
            for(String id:List.of("0","-1","1.0","1e0","9223372036854775808","18446744073709551616","event-id","01"))
                f.error(f.get(DELIVERIES+"/"+id),400);
            assertEquals(1,f.count("audit_log"));
        }
    }

    @Test void tiedTaskCreationTimesHaveStableIdDescendingSqlLimitedPages() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000);
            for(int i=0;i<25;i++) f.event("stable-"+i,"stable",endpoint);
            var expected=f.jdbc.queryForList("SELECT id FROM delivery_task ORDER BY created_at DESC,id DESC",Long.class);
            var first=f.page(""); var second=f.page("?page=2");
            assertEquals(25,first.path("total").asInt()); assertEquals(20,first.path("items").size()); assertEquals(5,second.path("items").size());
            var actual=new ArrayList<Long>(); first.path("items").forEach(n->actual.add(n.path("taskId").asLong()));
            second.path("items").forEach(n->actual.add(n.path("taskId").asLong())); assertEquals(expected,actual);
            for(var item:first.path("items")) { assertEquals(SUMMARY_FIELDS,item.propertyNames()); assertTrue(item.path("lastAttemptAt").isNull()); }
            for(String query:List.of("?page=3","?page=2147483647&size=100")) {
                var empty=f.page(query); assertEquals(25,empty.path("total").asInt()); assertEquals(0,empty.path("items").size());
            }
            assertEquals(1,f.page("?size=1").path("items").size()); assertEquals(25,f.page("?size=100").path("items").size());
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM delivery_task",Integer.class));
        }
    }

    @Test void allSixLatestStatusesAndEveryFilterUseExactEventIdentityAndEventTime() throws Exception {
        try(var f=new Fixture()) {
            long a=f.endpoint(5000), b=f.endpoint(5000);
            f.event("pending","Type.A",a);
            f.event("delivering","Type.A",a); f.claim("delivering");
            f.event("retry","Type.A",a); f.finish("retry",SingleDeliveryHttpResult.fromHttpResponse(500,EXCERPT));
            f.event("success","Type.B",a); f.finish("success",SingleDeliveryHttpResult.fromHttpResponse(204,""));
            f.event("final","Type.A",a); f.finish("final",SingleDeliveryHttpResult.fromHttpResponse(404,"old"));
            f.event("paused","Type.A",b); f.disable(b);
            for(String state:List.of("PENDING","DELIVERING","WAIT_RETRY","SUCCESS","FINAL_FAILED","PAUSED")) {
                var page=f.page("?status="+state); assertEquals(1,page.path("total").asInt()); assertEquals(state,page.path("items").get(0).path("status").asString());
            }
            assertEquals(5,f.page("?endpointId="+a).path("total").asInt()); assertEquals(5,f.page("?eventType=Type.A").path("total").asInt());
            var combined=f.page("?eventType=Type.A&status=WAIT_RETRY&endpointId="+a+"&from="+NOW+"&to="+NOW.plusMillis(1));
            assertEquals(1,combined.path("total").asInt()); assertEquals("retry",combined.path("items").get(0).path("eventId").asString());
            assertEquals(0,f.page("?eventType=Type.B&status=WAIT_RETRY").path("total").asInt());
            assertEquals(0,f.page("?status=WAIT_RETRY&endpointId="+b).path("total").asInt());
            f.clock.setInstant(NOW.plusMillis(1)); f.event("at-upper","boundary",a);
            f.clock.setInstant(NOW.minusMillis(1)); f.event("before-lower","boundary",a);
            assertEquals(6,f.page("?from="+NOW+"&to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(7,f.page("?from="+NOW).path("total").asInt()); assertEquals(7,f.page("?to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(0,f.page("?from=2026-10-30T03:40:00.123000001Z&to=2026-10-30T03:40:00.123999999Z").path("total").asInt());
            assertEquals(1,f.page("?eventType=boundary&from=2026-10-30T03:40:00.123000001Z").path("total").asInt());
            assertEquals(1,f.page("?eventType=boundary&to=2026-10-30T03:40:00.123000001Z").path("total").asInt());
        }
    }

    @Test void eventTypeIsLiteralCaseSensitiveNoPadAndUnicodeNotNormalized() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000);
            for(String type:List.of("Case","case"," Case ","é","é","😀".repeat(128),"literal%_","literalZZ","a' OR 1=1 --"))
                f.event(UUID.randomUUID().toString(),type,endpoint);
            for(String type:List.of("Case","case"," Case ","é","é","😀".repeat(128),"literal%_","a' OR 1=1 --")) {
                var page=f.page("?eventType="+enc(type)); assertEquals(1,page.path("total").asInt()); assertEquals(type,page.path("items").get(0).path("eventType").asString());
            }
            assertEquals(0,f.page("?eventType=CASE").path("total").asInt()); assertEquals(0,f.page("?eventType=%25").path("total").asInt());
        }
    }

    @Test void redeliveryReordersTasksButFilteringRetainsOriginalEventCreationAndNoOldAttemptTimeLeaks() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("older-event","order",endpoint); long old=f.task("older-event");
            f.finish("older-event",SingleDeliveryHttpResult.fromHttpResponse(404,"old")); var oldDetail=f.detail(old);
            f.clock.advance(Duration.ofMinutes(1)); f.event("newer-event","order",endpoint); long newer=f.task("newer-event");
            assertEquals(newer,f.page("?size=1").path("items").get(0).path("taskId").asLong());
            f.clock.advance(Duration.ofMinutes(1)); var response=f.redeliver("older-event"); long fresh=f.task("older-event");
            var page=f.page(""); assertEquals(2,page.path("total").asInt());
            assertEquals(List.of(fresh,newer),ids(page)); assertEquals(f.json(response),page.path("items").get(0));
            var item=page.path("items").get(0); assertEquals(2,item.path("roundNo").asInt()); assertTrue(item.path("lastAttemptAt").isNull());
            assertEquals(NOW.plusSeconds(120),Instant.parse(item.path("createdAt").asString()));
            var filtered=f.page("?from="+NOW+"&to="+NOW.plusMillis(1)); assertEquals(1,filtered.path("total").asInt()); assertEquals(List.of(fresh),ids(filtered));
            assertEquals(0,f.page("?from="+NOW.plusSeconds(120)).path("total").asInt());
            assertEquals(oldDetail,f.detail(old)); assertEquals(0,f.detail(fresh).path("attempts").size());
            f.finish("older-event",SingleDeliveryHttpResult.fromHttpResponse(500,"new first"));
            f.due("older-event"); var second=f.claim("older-event");
            var secondStarted=f.jdbc.queryForObject("SELECT started_at FROM delivery_attempt WHERE task_id=? AND attempt_no=2",LocalDateTime.class,fresh).toInstant(ZoneOffset.UTC);
            assertEquals(secondStarted,Instant.parse(f.page("?size=1").path("items").get(0).path("lastAttemptAt").asString()));
            f.clock.advance(Duration.ofMillis(456)); f.complete(second,SingleDeliveryHttpResult.fromHttpResponse(200,"ok"));
            assertEquals(secondStarted,Instant.parse(f.page("?size=1").path("items").get(0).path("lastAttemptAt").asString()));
            assertEquals(oldDetail,f.detail(old)); assertEquals(2,f.detail(fresh).path("attempts").size());
        }
    }

    @Test void detailReusesSafeSingleRoundFieldsAllResultsNullsAndStoredFourKiBExcerpt() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("history","history",endpoint); long first=f.task("history");
            f.finish("history",SingleDeliveryHttpResult.fromHttpResponse(500,"中".repeat(1365)+"x"));
            f.due("history"); f.finish("history",SingleDeliveryHttpResult.unknown());
            f.due("history"); f.finish("history",SingleDeliveryHttpResult.localFailure());
            var old=f.detail(first); f.assertStored(first,old); assertEquals(3,old.path("attempts").size()); assertEquals("FINAL_FAILED",old.path("status").asString());
            var attempts=old.path("attempts");
            assertEquals(List.of("HTTP_FAILURE","UNKNOWN","LOCAL_FAILURE"),results(attempts));
            assertEquals(4096,attempts.get(0).path("responseExcerpt").asString().getBytes(StandardCharsets.UTF_8).length);
            assertEquals("HTTP 结果未知",attempts.get(1).path("errorMessage").asString()); assertTrue(attempts.get(1).path("httpStatus").isNull());
            f.redeliver("history"); long second=f.task("history"); f.claim("history"); var started=f.detail(second); f.assertStored(second,started);
            assertEquals(1,started.path("attempts").size()); assertEquals("STARTED",started.path("attempts").get(0).path("result").asString());
            for(String field:List.of("endedAt","durationMs","httpStatus","responseExcerpt","errorCode","errorMessage")) assertTrue(started.path("attempts").get(0).path(field).isNull());
            assertTrue(started.path("finishedAt").isNull()); assertTrue(started.path("nextRunAt").isNull()); assertEquals(old,f.detail(first));
            f.event("successful","history",endpoint); f.finish("successful",SingleDeliveryHttpResult.fromHttpResponse(200,"ok"));
            var success=f.detail(f.task("successful")).path("attempts").get(0); assertEquals("SUCCESS",success.path("result").asString());
            assertTrue(success.path("errorCode").isNull()); assertTrue(success.path("errorMessage").isNull());
            // 同一安全轮模型的正式事件详情应与单任务详情逐字段一致。
            var event=f.read("/api/v1/admin/events/history");
            for(var round:event.path("rounds")) {
                var single=f.detail(round.path("taskId").asLong());
                for(String field:round.propertyNames()) assertEquals(round.path(field),single.path(field),field);
            }
        }
    }

    @Test void formalAdminTestEventScansToRealSimulatorThenNewRoundPreservesOldTaskThroughGet() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.mode("ALWAYS_500");
            var created=f.request("POST","/api/v1/admin/events/test",f.token,null,"{\"eventType\":\"formal\",\"endpointId\":"+endpoint+",\"payload\":{\"a\":1}}");
            assertEquals(202,created.statusCode()); String id=f.json(created).path("eventId").asString(); UUID.fromString(id); long old=f.task(id);
            for(int attempt=1;attempt<=3;attempt++) { f.scan(); f.awaitStatus(id,attempt==3?"FINAL_FAILED":"WAIT_RETRY"); if(attempt<3) f.due(id); }
            var oldDetail=f.detail(old); assertEquals(3,oldDetail.path("attempts").size());
            f.clock.advance(Duration.ofMinutes(2)); f.mode("ALWAYS_200"); f.redeliver(id); long fresh=f.task(id);
            var pending=f.page(""); assertEquals(1,pending.path("total").asInt()); assertEquals(List.of(fresh),ids(pending));
            assertTrue(pending.path("items").get(0).path("lastAttemptAt").isNull()); assertEquals(0,f.detail(fresh).path("attempts").size());
            f.scan(); f.awaitStatus(id,"SUCCESS"); assertEquals("SUCCESS",f.detail(fresh).path("status").asString());
            assertEquals(200,f.detail(fresh).path("attempts").get(0).path("httpStatus").asInt()); assertEquals(oldDetail,f.detail(old));
            assertEquals(1,f.page("?status=SUCCESS&from="+NOW+"&to="+NOW.plusMillis(1)).path("total").asInt());
            assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM simulator_request_log WHERE event_id=?",Integer.class,id));
            assertEquals(1,f.count("event"));
        }
    }

    @Test void realProcessedThenDelayShowsUnknownNotSimulatorPlanned200() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(1200);
            assertEquals(200,f.request("PUT","/api/v1/admin/simulator/config",f.token,null,"{\"mode\":\"PROCESSED_THEN_DELAY\",\"delayMs\":3500}").statusCode());
            f.event("unknown","timeout",endpoint); long task=f.task("unknown"); f.scan(); f.awaitStatus("unknown","WAIT_RETRY");
            var first=f.detail(task).path("attempts").get(0); assertEquals("UNKNOWN",first.path("result").asString());
            assertTrue(first.path("httpStatus").isNull()); assertTrue(first.path("responseExcerpt").isNull()); assertEquals("HTTP 结果未知",first.path("errorMessage").asString());
            assertEquals(200,f.jdbc.queryForObject("SELECT planned_status FROM simulator_request_log WHERE event_id='unknown'",Integer.class));
            assertNotNull(f.jdbc.queryForObject("SELECT processed_at FROM simulator_event_state WHERE event_id='unknown'",LocalDateTime.class));
            f.due("unknown"); f.scan(); f.awaitStatus("unknown","SUCCESS"); var history=f.detail(task).path("attempts");
            assertEquals(2,history.size()); assertEquals(first,history.get(0)); assertEquals("SUCCESS",history.get(1).path("result").asString());
        }
    }

    @Test void successfulQueriesAndDatabaseFailureResponsesDoNotWriteOrLeakCredentialsPayloadExcerptOrDiagnostics() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("safe","safe",endpoint); var claim=f.claim("safe"); f.secrets.add(claim.claimToken());
            f.complete(claim,SingleDeliveryHttpResult.fromHttpResponse(404,EXCERPT)); long task=f.task("safe");
            var logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME); var logs=new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                var before=f.snapshot(); f.page(""); f.detail(task); assertEquals(before,f.snapshot());
                for(String table:List.of("event","delivery_task","delivery_attempt")) {
                    f.jdbc.execute("RENAME TABLE "+table+" TO p902_unavailable");
                    try { f.error(f.get(DELIVERIES),500); f.error(f.get(DELIVERIES+"/"+task),500); }
                    finally { f.jdbc.execute("RENAME TABLE p902_unavailable TO "+table); }
                }
                assertEquals(before,f.snapshot()); f.page(""); f.detail(task);
                assertFalse(logs.list.isEmpty());
                for(var log:logs.list) { f.noSecrets(log.getFormattedMessage()); assertFalse(log.getFormattedMessage().contains(PAYLOAD)); assertFalse(log.getFormattedMessage().contains(EXCERPT)); assertNull(log.getThrowableProxy()); }
            } finally { logger.detachAppender(logs); logs.stop(); }
        }
    }

    @Test void countAndItemsStayInOneSnapshotAcrossCreationClaimCompletionAndRedeliveryCommits() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("snapshot","snapshot",endpoint); long old=f.task("snapshot");
            var hook=f.context.getBean(ObservedTasks.class).countHook();
            var creation=f.between(hook,()->f.page(""),()->f.event("new","other",endpoint));
            assertEquals(1,creation.path("total").asInt()); assertEquals(List.of(old),ids(creation));
            var claimed=new AtomicReference<DeliveryClaimService.ClaimedTask>();
            var claim=f.between(hook,()->f.page("?eventType=snapshot&status=PENDING"),()->claimed.set(f.claim("snapshot")));
            assertEquals(1,claim.path("total").asInt()); assertEquals("PENDING",claim.path("items").get(0).path("status").asString()); assertTrue(claim.path("items").get(0).path("lastAttemptAt").isNull());
            var completion=f.between(hook,()->f.page("?eventType=snapshot&status=DELIVERING"),()->f.complete(claimed.get(),SingleDeliveryHttpResult.fromHttpResponse(404,"old")));
            assertEquals(1,completion.path("total").asInt()); assertEquals("DELIVERING",completion.path("items").get(0).path("status").asString());
            var redelivery=f.between(hook,()->f.page("?eventType=snapshot&status=FINAL_FAILED"),()->f.redeliver("snapshot"));
            assertEquals(1,redelivery.path("total").asInt()); assertEquals(List.of(old),ids(redelivery)); assertEquals(1,redelivery.path("items").get(0).path("roundNo").asInt());
            var next=f.page("?eventType=snapshot"); assertEquals(1,next.path("total").asInt()); assertEquals(2,next.path("items").get(0).path("roundNo").asInt()); assertTrue(next.path("items").get(0).path("lastAttemptAt").isNull());
        }
    }

    @Test void taskIdentityStatusAndAttemptsShareSnapshotAcrossCompletionAndNewRound() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("snapshot-detail","snapshot",endpoint); long old=f.task("snapshot-detail"); var claim=f.claim("snapshot-detail");
            var hook=f.context.getBean(ObservedHistory.class).taskHook();
            f.event("pending-detail","snapshot",endpoint); long pendingTask=f.task("pending-detail");
            var beforeClaim=f.between(hook,()->f.detail(pendingTask),()->f.claim("pending-detail"));
            assertEquals("PENDING",beforeClaim.path("status").asString()); assertEquals(0,beforeClaim.path("attemptCount").asInt());
            assertEquals(0,beforeClaim.path("attempts").size()); assertEquals(1,f.detail(pendingTask).path("attempts").size());
            var completion=f.between(hook,()->f.detail(old),()->f.complete(claim,SingleDeliveryHttpResult.fromHttpResponse(404,"old")));
            assertEquals("DELIVERING",completion.path("status").asString()); assertEquals("STARTED",completion.path("attempts").get(0).path("result").asString());
            var failed=f.detail(old); var redelivery=f.between(hook,()->f.detail(old),()->f.redeliver("snapshot-detail"));
            assertEquals(failed,redelivery); assertEquals(failed,f.detail(old)); assertEquals(0,f.detail(f.task("snapshot-detail")).path("attempts").size());
        }
    }

    @Test void queryReturnsCommittedSnapshotWhileClaimCompletionPauseResumeRecoveryAndRedeliveryStillHoldRealWriteLocks() throws Exception {
        try(var f=new Fixture()) {
            long endpoint=f.endpoint(5000); f.event("uncommitted","locks",endpoint); var claimed=new AtomicReference<DeliveryClaimService.ClaimedTask>();
            f.whileUncommitted("uncommitted",()->claimed.set(f.claim("uncommitted")),"PENDING",1,"none");
            f.whileUncommitted("uncommitted",()->f.complete(claimed.get(),SingleDeliveryHttpResult.fromHttpResponse(500,"retry")),"DELIVERING",1,"STARTED");
            f.whileUncommitted("uncommitted",()->f.disable(endpoint),"WAIT_RETRY",1,"HTTP_FAILURE");
            f.whileUncommitted("uncommitted",()->f.enable(endpoint),"PAUSED",1,"HTTP_FAILURE");
            f.due("uncommitted"); claimed.set(f.claim("uncommitted")); f.clock.setInstant(claimed.get().leaseUntil().toInstant(ZoneOffset.UTC));
            f.whileUncommitted("uncommitted",()->assertTrue(f.context.getBean(DeliveryRecoveryService.class).recover(f.task("uncommitted"),claimed.get().claimToken())),"DELIVERING",1,"STARTED");
            var recovered=f.detail(f.task("uncommitted")).path("attempts").get(1);
            assertEquals("UNKNOWN",recovered.path("result").asString()); assertEquals("EXECUTOR_INTERRUPTED",recovered.path("errorCode").asString());
            assertEquals("执行器中断，HTTP结果未知",recovered.path("errorMessage").asString()); assertTrue(recovered.path("httpStatus").isNull()); assertTrue(recovered.path("responseExcerpt").isNull());
            f.due("uncommitted"); f.finish("uncommitted",SingleDeliveryHttpResult.fromHttpResponse(404,"failed"));
            f.whileUncommitted("uncommitted",()->f.context.getBean(DeliveryRedeliveryService.class).redeliver("uncommitted",f.actor,UUID.randomUUID().toString()),"FINAL_FAILED",1,"HTTP_FAILURE");
            assertEquals(2,f.page("").path("items").get(0).path("roundNo").asInt());
        }
    }

    static List<Long> ids(JsonNode page) { var ids=new ArrayList<Long>(); page.path("items").forEach(n->ids.add(n.path("taskId").asLong())); return ids; }
    static List<String> results(JsonNode attempts) { var results=new ArrayList<String>(); attempts.forEach(n->results.add(n.path("result").asString())); return results; }
    static String enc(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    static JdbcTemplate independent() { var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())); jdbc.setQueryTimeout(3); return jdbc; }
    static void await(Supplier<Boolean> condition) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition.get()) { if(System.nanoTime()>=end) fail("有界等待超时"); Thread.sleep(10); } }
    static void waitLatch(CountDownLatch latch) { try { assertTrue(latch.await(4,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }

    static class Fixture implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final JsonMapper mapper=JsonMapper.builder().build();
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
                    "--HOOKFLOW_ADMIN_USERNAME=p902-admin","--HOOKFLOW_ADMIN_PASSWORD=p902-admin-password","--HOOKFLOW_SECRET_ENCRYPTION_KEY="+encryption,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc=context.getBean(JdbcTemplate.class); clock=context.getBean(MutableClock.class);
            base="http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port");
            var login=request("POST","/api/v1/auth/login",null,null,"{\"username\":\"p902-admin\",\"password\":\"p902-admin-password\"}"); assertEquals(200,login.statusCode()); token=json(login).path("token").asString();
            var rotate=request("POST","/api/v1/admin/api-key/rotate",token,null,null); assertEquals(200,rotate.statusCode()); business=json(rotate).path("apiKey").asString();
            secrets.addAll(List.of(token,business,encryption,"p902-admin-password")); actor=jdbc.queryForObject("SELECT id FROM app_user WHERE username='p902-admin'",Long.class);
        }
        HttpResponse<String> request(String method,String path,String admin,String key,String body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(7)).header("X-Request-Id","forged-p902");
            if(admin!=null) builder.header("Authorization","Bearer "+admin); if(key!=null) builder.header("X-HookFlow-Api-Key",key);
            if(body!=null) builder.header("Content-Type","application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return request("GET",path,token,null,null); }
        JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
        JsonNode page(String query) throws Exception { return read(DELIVERIES+query); }
        JsonNode detail(long task) throws Exception { var detail=read(DELIVERIES+"/"+task); assertEquals(DETAIL_FIELDS,detail.propertyNames()); for(var attempt:detail.path("attempts")) assertEquals(ATTEMPT_FIELDS,attempt.propertyNames()); return detail; }
        void assertStored(long taskId,JsonNode detail) {
            var task=jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?",taskId);
            assertEquals(taskId,detail.path("taskId").asLong());
            assertEquals(jdbc.queryForObject("SELECT event_id FROM event WHERE id=?",String.class,task.get("event_pk")),detail.path("eventId").asString());
            for(String[] field:List.of(new String[]{"round_no","roundNo"},new String[]{"status","status"},new String[]{"attempt_count","attemptCount"},new String[]{"max_attempts","maxAttempts"},new String[]{"next_run_at","nextRunAt"},new String[]{"created_at","createdAt"},new String[]{"finished_at","finishedAt"}))
                storedValue(task.get(field[0]),detail.path(field[1]));
            var attempts=jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=? ORDER BY attempt_no ASC",taskId);
            assertEquals(attempts.size(),detail.path("attempts").size());
            for(int i=0;i<attempts.size();i++) {
                var stored=attempts.get(i); var view=detail.path("attempts").get(i);
                for(String[] field:List.of(new String[]{"attempt_no","attemptNo"},new String[]{"result","result"},new String[]{"started_at","startedAt"},new String[]{"ended_at","endedAt"},new String[]{"duration_ms","durationMs"},new String[]{"http_status","httpStatus"},new String[]{"response_excerpt","responseExcerpt"},new String[]{"error_code","errorCode"},new String[]{"error_message","errorMessage"}))
                    storedValue(stored.get(field[0]),view.path(field[1]));
            }
        }
        void storedValue(Object stored,JsonNode view) {
            if(stored==null) assertTrue(view.isNull());
            else if(stored instanceof LocalDateTime time) { assertTrue(view.asString().endsWith("Z")); assertEquals(time.toInstant(ZoneOffset.UTC),Instant.parse(view.asString())); }
            else if(stored instanceof Number number) assertEquals(number.longValue(),view.asLong());
            else assertEquals(stored,view.asString());
        }
        JsonNode read(String path) throws Exception {
            var response=get(path); assertEquals(200,response.statusCode(),response.body()); noSecrets(response.body()); noAudit(response); if(path.startsWith(DELIVERIES)) assertFalse(response.body().contains(PAYLOAD));
            var result=json(response); if(path.startsWith(DELIVERIES) && result.has("items")) for(var item:result.path("items")) assertEquals(SUMMARY_FIELDS,item.propertyNames()); return result;
        }
        void noAudit(HttpResponse<String> response) { String id=response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(id).toString(),id); assertNotEquals("forged-p902",id); assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Integer.class,id)); }
        void noSecrets(String text) {
            for(String secret:secrets) assertFalse(text.contains(secret));
            for(String name:List.of("secretCiphertext","secret_ciphertext","claimToken","claim_token","signingSecret","token_hash","key_hash","p902_unavailable","SELECT ","BadSqlGrammarException:")) assertFalse(text.contains(name),name);
        }
        void error(HttpResponse<String> response,int status) {
            assertEquals(status,response.statusCode(),response.body()); var error=json(response); assertEquals(Set.of("code","message","requestId"),error.propertyNames());
            assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),error.path("requestId").asString()); noAudit(response); noSecrets(response.body()); assertFalse(response.body().contains(PAYLOAD));
            assertEquals(switch(status) { case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; default -> "INTERNAL_ERROR"; },error.path("code").asString());
        }
        long endpoint(int timeout) throws Exception {
            var response=request("POST","/api/v1/admin/endpoints",token,null,"{\"name\":\"p902\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":"+timeout+"}"); assertEquals(201,response.statusCode());
            long id=json(response).path("id").asLong(); byte[] ciphertext=jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?",(rs,row)->rs.getBytes(1),id);
            secrets.add(context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(ciphertext)); secrets.add(Base64.getEncoder().encodeToString(ciphertext)); return id;
        }
        void event(String id,String type,long endpoint) throws Exception {
            var response=request("POST","/api/v1/events",null,business,"{\"eventId\":"+mapper.writeValueAsString(id)+",\"eventType\":"+mapper.writeValueAsString(type)+",\"endpointId\":"+endpoint+",\"payload\":{\"marker\":\""+PAYLOAD+"\"}}"); assertEquals(202,response.statusCode(),response.body());
        }
        long task(String id) { return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no",Long.class,id); }
        DeliveryClaimService.ClaimedTask claim(String id) { return context.getBean(DeliveryClaimService.class).claim(task(id)).orElseThrow(); }
        void complete(DeliveryClaimService.ClaimedTask claim,SingleDeliveryHttpResult result) { assertTrue(context.getBean(DeliveryCompletionService.class).complete(claim.taskId(),claim.attemptNo(),claim.claimToken(),result)); }
        void finish(String id,SingleDeliveryHttpResult result) { complete(claim(id),result); }
        void due(String id) { clock.setInstant(jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?",LocalDateTime.class,task(id)).toInstant(ZoneOffset.UTC)); }
        void disable(long endpoint) { context.getBean(EndpointDisableService.class).disable(endpoint,actor,UUID.randomUUID().toString()); }
        void enable(long endpoint) { context.getBean(EndpointEnableService.class).enable(endpoint,actor,UUID.randomUUID().toString()); }
        HttpResponse<String> redeliver(String id) throws Exception { var response=request("POST","/api/v1/admin/events/"+enc(id)+"/redeliver",token,null,null); assertEquals(202,response.statusCode(),response.body()); return response; }
        void mode(String mode) throws Exception { assertEquals(200,request("PUT","/api/v1/admin/simulator/config",token,null,"{\"mode\":\""+mode+"\"}").statusCode()); }
        void scan() { context.getBean(DueDeliveryScanner.class).scanDueTasks(); }
        void awaitStatus(String id,String status) throws Exception { await(()->status.equals(jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?",String.class,id))); }
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
            long task=task(id); var worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
            try {
                tx().executeWithoutResult(status->{
                    write.run(); long connection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                    var observer=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())); observer.setQueryTimeout(2);
                    assertTrue(observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_NAME='event' AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED'",Long.class,connection)>0);
                    Future<?> read=worker.submit(()->{
                        try {
                            var page=page("?eventType=locks"); assertEquals(1,page.path("total").asInt()); var item=page.path("items").get(0); assertEquals(expected,item.path("status").asString()); assertEquals(task,item.path("taskId").asLong()); assertEquals(round,item.path("roundNo").asInt());
                            var detail=detail(task); assertEquals(expected,detail.path("status").asString()); assertEquals(round,detail.path("roundNo").asInt()); var attempts=detail.path("attempts");
                            if(lastResult.equals("none")) { assertEquals(0,attempts.size()); assertTrue(item.path("lastAttemptAt").isNull()); }
                            else { assertEquals(lastResult,attempts.get(attempts.size()-1).path("result").asString()); assertEquals(attempts.get(attempts.size()-1).path("startedAt"),item.path("lastAttemptAt")); }
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
