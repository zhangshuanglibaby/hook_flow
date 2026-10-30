package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.audit.infrastructure.AuditQueryRepository;
import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.DeliveryClaimService;
import com.hookflow.delivery.application.DeliveryCompletionService;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.application.EndpointUpdateService;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AuditQueryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final Instant NOW = Instant.parse("2026-10-30T04:45:00.123Z");
    static final String PATH = "/api/v1/admin/audit-logs";
    static final Set<String> FIELDS = Set.of("id", "actorUserId", "action", "objectType", "objectId", "occurredAt", "requestId");
    static final String PAYLOAD = "p904-payload-never-return-or-log";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock auditClock() { return new MutableClock(NOW); }
        @Bean @Primary ObservedAudits observedAudits(JdbcTemplate jdbc) { return new ObservedAudits(jdbc); }
    }
    static class ObservedAudits extends AuditQueryRepository {
        final JdbcTemplate jdbc;
        final AtomicReference<Runnable> hook = new AtomicReference<>();
        final ThreadLocal<Long> connection = new ThreadLocal<>();
        ObservedAudits(JdbcTemplate jdbc) { super(jdbc); this.jdbc = jdbc; }
        AtomicReference<Runnable> hook() { return hook; }
        @Override public long count() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            long total = super.count(); var action = hook.getAndSet(null); if (action != null) action.run(); return total;
        }
        @Override public List<com.hookflow.audit.application.AuditQueryService.Entry> page(PageParameters page) {
            assertEquals(connection.get(), jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            connection.remove(); return super.page(page);
        }
    }

    @AfterEach void clear() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.setQueryTimeout(3);
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config", "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint", "api_key")) jdbc.update("DELETE FROM " + table);
    }

    @Test void emptyDatabaseUsesFormalLoginAndBusinessKeyCannotReadAudit() throws Exception {
        try (var f = new Fixture()) {
            var empty = f.page(""); assertEquals(0, empty.path("total").asLong()); assertEquals(0, empty.path("items").size());
            assertEquals(1, empty.path("page").asInt()); assertEquals(20, empty.path("size").asInt());
            f.error(f.request("GET", PATH, null, null, null), 401);
            var rotated = f.rotate(); f.audit(rotated, "API_KEY_ROTATED", "BUSINESS_API_KEY", "1");
            f.error(f.request("GET", PATH, null, f.business, null), 403);
            assertEquals(1, f.page("").path("total").asLong());
        }
    }

    @Test void invalidPaginationUsesSafe400AndNoQueryAudit() throws Exception {
        try (var f = new Fixture()) {
            for (String query : List.of("page=0", "page=-1", "page=", "page=%20", "page=1.0", "page=1e0", "page=01", "page=2147483648", "page=9223372036854775808", "size=0", "size=-1", "size=", "size=101", "size=1.0", "size=2147483648")) f.error(f.get(PATH + "?" + query), 400);
            assertEquals(0, f.count("audit_log"));
        }
    }

    @Test void tiedTimesAndDifferentTimesHaveStableDatabaseLimitedPagesAndNoImplicitFilters() throws Exception {
        try (var f = new Fixture()) {
            f.rotate();
            for (int i = 0; i < 24; i++) f.endpoint(false);
            var expected = f.jdbc.queryForList("SELECT id FROM audit_log ORDER BY occurred_at DESC,id DESC", Long.class);
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(DISTINCT occurred_at) FROM audit_log", Long.class));
            var before = f.snapshot(); var first = f.page(""); var second = f.page("?page=2");
            assertEquals(25, first.path("total").asLong()); assertEquals(20, first.path("items").size()); assertEquals(5, second.path("items").size());
            var ids = ids(first); ids.addAll(ids(second)); assertEquals(expected, ids);
            for (String query : List.of("?page=3", "?page=2147483647&size=100")) {
                var empty = f.page(query); assertEquals(25, empty.path("total").asLong()); assertEquals(0, empty.path("items").size());
            }
            assertEquals(1, f.page("?size=1").path("items").size()); assertEquals(25, f.page("?size=100").path("items").size());
            assertEquals(first, f.page("?action=missing&actorUserId=999&from=bad"));
            assertEquals(before, f.snapshot());
            f.clock.advance(Duration.ofSeconds(1)); long endpoint = f.endpoint(false);
            f.clock.setInstant(NOW.minusSeconds(1)); f.endpoint(false);
            assertEquals(Long.toString(endpoint), f.page("?size=1").path("items").get(0).path("objectId").asString());
            f.assertStored(f.page("?size=100"));
        }
    }

    @Test void allSevenActionsAreQueryableWithTrustedActorInternalObjectUtcAndOriginalRequestId() throws Exception {
        try (var f = new Fixture()) {
            f.audit(f.rotate(), "API_KEY_ROTATED", "BUSINESS_API_KEY", "1");
            var created = f.createEndpoint(false); long endpoint = f.json(created).path("id").asLong();
            f.audit(created, "ENDPOINT_CREATED", "ENDPOINT", Long.toString(endpoint));
            f.clock.advance(Duration.ofMillis(1));
            var edited = f.admin("PATCH", "/api/v1/admin/endpoints/" + endpoint, "{\"name\":\"updated\",\"timeoutMs\":7000}");
            f.audit(edited, "ENDPOINT_UPDATED", "ENDPOINT", Long.toString(endpoint));
            f.audit(f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/disable", null), "ENDPOINT_DISABLED", "ENDPOINT", Long.toString(endpoint));
            f.audit(f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null), "ENDPOINT_ENABLED", "ENDPOINT", Long.toString(endpoint));
            var config = f.admin("PUT", "/api/v1/admin/simulator/config", "{\"mode\":\"ALWAYS_500\"}");
            f.audit(config, "SIMULATOR_CONFIG_UPDATED", "SIMULATOR_CONFIG", "1");
            String event = f.testEvent(endpoint); f.failEvent(event);
            var redeliver = f.admin("POST", "/api/v1/admin/events/" + enc(event) + "/redeliver", null);
            assertEquals(202, redeliver.statusCode());
            String eventPk = f.jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM event WHERE event_id=?", String.class, event);
            f.audit(redeliver, "EVENT_REDELIVERED", "EVENT", eventPk);
            var all = f.page("?size=100"); f.assertStored(all);
            var actions = new HashSet<String>(); all.path("items").forEach(item -> actions.add(item.path("action").asString()));
            assertEquals(Set.of("API_KEY_ROTATED", "ENDPOINT_CREATED", "ENDPOINT_UPDATED", "ENDPOINT_DISABLED", "ENDPOINT_ENABLED", "SIMULATOR_CONFIG_UPDATED", "EVENT_REDELIVERED"), actions);
            assertEquals(7, all.path("total").asLong());
            assertEquals(2, f.jdbc.queryForObject("SELECT latest_round_no FROM event WHERE event_id=?", Integer.class, event));
        }
    }

    @Test void defaultsNoOpsAndFailedOperationsNeverInventSuccessfulAudit() throws Exception {
        try (var f = new Fixture()) {
            f.rotate(); long endpoint = f.endpoint(false);
            f.noAudit(f.get("/api/v1/admin/simulator/config"));
            f.noAudit(f.admin("PUT", "/api/v1/admin/simulator/config", "{\"mode\":\"ALWAYS_200\"}"));
            var enable = f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null); f.noAudit(enable);
            var disable = f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/disable", null); assertEquals(200, disable.statusCode());
            var before = f.snapshot(); var again = f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/disable", null); f.noAudit(again); assertEquals(before, f.snapshot());
            f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null);
            before = f.snapshot(); f.noAudit(f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null)); assertEquals(before, f.snapshot());
            String event = f.testEvent(endpoint); before = f.snapshot();
            f.error(f.admin("POST", "/api/v1/admin/events/" + enc(event) + "/redeliver", null), 409);
            f.error(f.admin("PATCH", "/api/v1/admin/endpoints/" + endpoint, "{}"), 400);
            f.error(f.admin("PATCH", "/api/v1/admin/endpoints/9223372036854775807", "{\"name\":\"missing\"}"), 404);
            f.error(f.admin("POST", "/api/v1/admin/endpoints", "{\"name\":\"bad\",\"targetType\":\"EXTERNAL\",\"url\":\"http://127.0.0.1/\"}"), 422);
            assertEquals(before, f.snapshot());
            f.admin("PUT", "/api/v1/admin/simulator/config", "{\"mode\":\"ALWAYS_500\"}"); before = f.snapshot();
            f.noAudit(f.admin("PUT", "/api/v1/admin/simulator/config", "{\"mode\":\"ALWAYS_500\"}")); assertEquals(before, f.snapshot());
        }
    }

    @Test void auditInsertionFailureRollsBackEveryKindOfFormalManagementOperation() throws Exception {
        try (var f = new Fixture()) {
            f.rotate(); long endpoint = f.endpoint(false); String event = f.testEvent(endpoint); f.failEvent(event);
            for (String action : List.of("API_KEY_ROTATED", "ENDPOINT_CREATED", "ENDPOINT_UPDATED", "ENDPOINT_DISABLED", "ENDPOINT_ENABLED", "EVENT_REDELIVERED", "SIMULATOR_CONFIG_UPDATED")) {
                if (action.equals("ENDPOINT_ENABLED")) f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/disable", null);
                if (action.equals("EVENT_REDELIVERED")) f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null);
                f.clock.advance(Duration.ofSeconds(1)); var before = f.snapshot();
                // 临时测试库CHECK只拒绝此时刻之后的指定成功审计，保留已有合法历史。
                String cutoff = LocalDateTime.ofInstant(f.clock.instant().minusMillis(1), ZoneOffset.UTC).toString().replace('T', ' ');
                f.jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT p904_reject_audit CHECK (action <> '" + action + "' OR occurred_at <= '" + cutoff + "')");
                try {
                    var response = switch (action) {
                        case "API_KEY_ROTATED" -> f.admin("POST", "/api/v1/admin/api-key/rotate", null);
                        case "ENDPOINT_CREATED" -> f.admin("POST", "/api/v1/admin/endpoints", "{\"name\":\"rollback\",\"targetType\":\"SIMULATOR\"}");
                        case "ENDPOINT_UPDATED" -> f.admin("PATCH", "/api/v1/admin/endpoints/" + endpoint, "{\"name\":\"rollback\"}");
                        case "ENDPOINT_DISABLED" -> f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/disable", null);
                        case "ENDPOINT_ENABLED" -> f.admin("POST", "/api/v1/admin/endpoints/" + endpoint + "/enable", null);
                        case "EVENT_REDELIVERED" -> f.admin("POST", "/api/v1/admin/events/" + enc(event) + "/redeliver", null);
                        default -> f.admin("PUT", "/api/v1/admin/simulator/config", "{\"mode\":\"ALWAYS_500\"}");
                    };
                    f.error(response, 500); assertEquals(before, f.snapshot(), action); f.assertStored(f.page("?size=100"));
                } finally { f.jdbc.execute("ALTER TABLE audit_log DROP CHECK p904_reject_audit"); }
            }
        }
    }

    @Test void countAndItemsRemainOneSnapshotWhenFormalManagementAuditCommitsBetweenThem() throws Exception {
        try (var f = new Fixture()) {
            f.rotate(); long endpoint = f.endpoint(false); var before = f.page("?size=100");
            var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
            var slot = f.context.getBean(ObservedAudits.class).hook(); slot.set(() -> { reached.countDown(); waitLatch(release); });
            var worker = worker(); Future<JsonNode> reading = worker.submit(() -> f.page("?size=100"));
            try {
                assertTrue(reached.await(4, TimeUnit.SECONDS)); f.clock.advance(Duration.ofMillis(1));
                f.audit(f.admin("PATCH", "/api/v1/admin/endpoints/" + endpoint, "{\"name\":\"concurrent\"}"), "ENDPOINT_UPDATED", "ENDPOINT", Long.toString(endpoint));
                release.countDown(); assertEquals(before, reading.get(5, TimeUnit.SECONDS));
                var after = f.page("?size=100"); assertEquals(before.path("total").asLong() + 1, after.path("total").asLong()); f.assertStored(after);
            } finally { release.countDown(); slot.set(null); stop(worker); }
        }
    }

    @Test void independentHttpReadCompletesWhileFormalWriterStillHoldsRealEndpointLockAndUncommittedAudit() throws Exception {
        try (var f = new Fixture()) {
            f.rotate(); long endpoint = f.endpoint(false); var before = f.page("?size=100"); var worker = worker();
            try {
                var tx = new TransactionTemplate(f.context.getBean(PlatformTransactionManager.class)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15);
                tx.executeWithoutResult(status -> {
                    f.context.getBean(EndpointUpdateService.class).update(endpoint, "uncommitted", 6000, f.actor, UUID.randomUUID().toString());
                    long connection = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())); observer.setQueryTimeout(2);
                    assertTrue(observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_NAME='endpoint' AND l.INDEX_NAME='PRIMARY' AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED' AND l.LOCK_DATA=?", Long.class, connection, Long.toString(endpoint)) > 0);
                    assertEquals(before.path("total").asLong(), observer.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class));
                    assertEquals(before.path("total").asLong() + 1, f.count("audit_log"));
                    Future<JsonNode> reading = worker.submit(() -> f.page("?size=100"));
                    try { assertEquals(before, reading.get(4, TimeUnit.SECONDS)); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                    assertFalse(status.isCompleted());
                });
                assertEquals(before.path("total").asLong() + 1, f.page("?size=100").path("total").asLong());
            } finally { stop(worker); }
        }
    }

    @Test void realDatabaseReadFailureIsSafe500AndSuccessfulReadsNeverWriteOrLeak() throws Exception {
        try (var f = new Fixture()) {
            f.rotate(); f.endpoint(true);
            var logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME); var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                var before = f.snapshot(); f.page(""); assertEquals(before, f.snapshot());
                f.jdbc.execute("RENAME TABLE audit_log TO p904_unavailable");
                try {
                    var response = f.get(PATH); assertEquals(500, response.statusCode()); var error = f.json(response);
                    assertEquals(Set.of("code", "message", "requestId"), error.propertyNames()); assertEquals("INTERNAL_ERROR", error.path("code").asString());
                    assertEquals(f.requestId(response), error.path("requestId").asString()); f.noSecrets(response.body());
                } finally { f.jdbc.execute("RENAME TABLE p904_unavailable TO audit_log"); }
                assertEquals(before, f.snapshot()); f.page(""); assertFalse(logs.list.isEmpty());
                for (var log : logs.list) { f.noSecrets(log.getFormattedMessage()); assertNull(log.getThrowableProxy()); assertNotNull(log.getMDCPropertyMap().get("requestId")); }
            } finally { logger.detachAppender(logs); logs.stop(); }
        }
    }

    static List<Long> ids(JsonNode page) { var result = new ArrayList<Long>(); page.path("items").forEach(item -> result.add(item.path("id").asLong())); return result; }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static ThreadPoolExecutor worker() { return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)); }
    static void stop(ThreadPoolExecutor worker) throws InterruptedException { worker.shutdownNow(); assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS)); }
    static void waitLatch(CountDownLatch latch) { try { assertTrue(latch.await(4, TimeUnit.SECONDS)); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); } }

    static class Fixture implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final JsonMapper mapper = JsonMapper.builder().build();
        final ConfigurableApplicationContext context;
        final JdbcTemplate jdbc;
        final MutableClock clock;
        final String base, token, encryption;
        String business;
        final long actor;
        final List<String> secrets = new ArrayList<>();
        Fixture() throws Exception {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key); encryption = Base64.getEncoder().encodeToString(key);
            context = new SpringApplicationBuilder(HookflowBackendApplication.class, Configuration.class).run(
                    "--spring.profiles.active=p2-login-test", "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p904-admin", "--HOOKFLOW_ADMIN_PASSWORD=p904-admin-password", "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + encryption,
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=5");
            jdbc = context.getBean(JdbcTemplate.class); clock = context.getBean(MutableClock.class);
            base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            var login = request("POST", "/api/v1/auth/login", null, null, "{\"username\":\"p904-admin\",\"password\":\"p904-admin-password\"}");
            assertEquals(200, login.statusCode()); token = json(login).path("token").asString(); secrets.addAll(List.of(token, encryption, "p904-admin-password"));
            actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='p904-admin'", Long.class);
        }
        HttpResponse<String> request(String method, String path, String admin, String key, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(7)).header("X-Request-Id", "forged-p904");
            if (admin != null) builder.header("Authorization", "Bearer " + admin); if (key != null) builder.header("X-HookFlow-Api-Key", key); if (body != null) builder.header("Content-Type", "application/json");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> admin(String method, String path, String body) throws Exception { return request(method, path, token, null, body); }
        HttpResponse<String> get(String path) throws Exception { return admin("GET", path, null); }
        JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
        String requestId(HttpResponse<String> response) { String id = response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(UUID.fromString(id).toString(), id); assertNotEquals("forged-p904", id); return id; }
        void noAudit(HttpResponse<String> response) { assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?", Long.class, requestId(response))); }
        JsonNode page(String query) throws Exception {
            var response = get(PATH + query); assertEquals(200, response.statusCode(), response.body()); noAudit(response); noSecrets(response.body()); var result = json(response);
            assertEquals(Set.of("page", "size", "total", "items"), result.propertyNames()); assertTrue(result.path("total").isIntegralNumber());
            for (var item : result.path("items")) { assertEquals(FIELDS, item.propertyNames()); assertTrue(item.path("id").isIntegralNumber()); assertTrue(item.path("actorUserId").isIntegralNumber()); assertTrue(item.path("objectId").isString()); assertTrue(item.path("occurredAt").asString().endsWith("Z")); assertNotEquals(requestId(response), item.path("requestId").asString()); }
            return result;
        }
        void assertStored(JsonNode page) {
            var rows = jdbc.queryForList("SELECT * FROM audit_log ORDER BY occurred_at DESC,id DESC"); assertEquals(rows.size(), page.path("total").asLong()); assertEquals(rows.size(), page.path("items").size());
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i); var item = page.path("items").get(i);
                assertEquals(((Number) row.get("id")).longValue(), item.path("id").asLong()); assertEquals(((Number) row.get("actor_user_id")).longValue(), item.path("actorUserId").asLong());
                assertEquals(row.get("action"), item.path("action").asString()); assertEquals(row.get("object_type"), item.path("objectType").asString()); assertEquals(row.get("object_id"), item.path("objectId").asString()); assertEquals(row.get("request_id"), item.path("requestId").asString());
                assertEquals(((LocalDateTime) row.get("occurred_at")).toInstant(ZoneOffset.UTC), Instant.parse(item.path("occurredAt").asString()));
            }
        }
        void audit(HttpResponse<String> operation, String action, String type, String object) throws Exception {
            assertTrue(operation.statusCode() >= 200 && operation.statusCode() < 300, operation.body()); String id = requestId(operation);
            var page = page("?size=100"); var entries = new ArrayList<JsonNode>(); page.path("items").forEach(item -> { if (id.equals(item.path("requestId").asString())) entries.add(item); });
            assertEquals(1, entries.size()); var entry = entries.getFirst(); assertEquals(actor, entry.path("actorUserId").asLong()); assertEquals(action, entry.path("action").asString()); assertEquals(type, entry.path("objectType").asString()); assertEquals(object, entry.path("objectId").asString());
            assertEquals(clock.instant(), Instant.parse(entry.path("occurredAt").asString()));
            if (type.equals("ENDPOINT")) assertEquals(clock.instant(), jdbc.queryForObject("SELECT updated_at FROM endpoint WHERE id=?", LocalDateTime.class, Long.parseLong(object)).toInstant(ZoneOffset.UTC));
            if (type.equals("SIMULATOR_CONFIG")) assertEquals(clock.instant(), jdbc.queryForObject("SELECT updated_at FROM simulator_config WHERE id=1", LocalDateTime.class).toInstant(ZoneOffset.UTC));
            if (type.equals("EVENT")) assertEquals(clock.instant(), jdbc.queryForObject("SELECT created_at FROM delivery_task WHERE event_pk=? ORDER BY round_no DESC LIMIT 1", LocalDateTime.class, Long.parseLong(object)).toInstant(ZoneOffset.UTC));
            if (type.equals("BUSINESS_API_KEY")) assertEquals(clock.instant(), jdbc.queryForObject("SELECT created_at FROM api_key WHERE revoked_at IS NULL", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        }
        void error(HttpResponse<String> response, int status) {
            assertEquals(status, response.statusCode(), response.body()); var result = json(response); assertEquals(Set.of("code", "message", "requestId"), result.propertyNames()); assertEquals(requestId(response), result.path("requestId").asString()); noAudit(response); noSecrets(response.body());
            assertEquals(switch (status) { case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; case 409 -> "CONFLICT"; case 422 -> "TARGET_NOT_ALLOWED"; default -> "INTERNAL_ERROR"; }, result.path("code").asString());
        }
        void noSecrets(String text) { for (String secret : secrets) assertFalse(text.contains(secret)); for (String value : List.of(PAYLOAD, "payload", "signingSecret", "secret_ciphertext", "claim_token", "token_hash", "key_hash", "p904_unavailable", "p904_reject_audit", "SELECT ", "INSERT INTO", "BadSqlGrammarException:")) assertFalse(text.contains(value), value); }
        HttpResponse<String> rotate() throws Exception { var response = admin("POST", "/api/v1/admin/api-key/rotate", null); assertEquals(200, response.statusCode()); business = json(response).path("apiKey").asString(); secrets.add(business); return response; }
        HttpResponse<String> createEndpoint(boolean external) throws Exception {
            var response = admin("POST", "/api/v1/admin/endpoints", external ? "{\"name\":\"external\",\"targetType\":\"EXTERNAL\",\"url\":\"https://example.com/hook\"}" : "{\"name\":\"simulator\",\"targetType\":\"SIMULATOR\"}"); assertEquals(201, response.statusCode());
            long id = json(response).path("id").asLong(); byte[] cipher = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs,row) -> rs.getBytes(1), id); secrets.add(Base64.getEncoder().encodeToString(cipher)); secrets.add(context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(cipher)); return response;
        }
        long endpoint(boolean external) throws Exception { return json(createEndpoint(external)).path("id").asLong(); }
        String testEvent(long endpoint) throws Exception { var response = admin("POST", "/api/v1/admin/events/test", "{\"eventType\":\"audit.test\",\"endpointId\":" + endpoint + ",\"payload\":{\"marker\":\"" + PAYLOAD + "\"}}"); assertEquals(202, response.statusCode()); noAudit(response); return json(response).path("eventId").asString(); }
        void failEvent(String event) {
            long task = jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND t.round_no=e.latest_round_no", Long.class, event);
            var claim = context.getBean(DeliveryClaimService.class).claim(task).orElseThrow(); secrets.add(claim.claimToken());
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(task, claim.attemptNo(), claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(404, "p904-excerpt")));
        }
        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
        Map<String,Object> snapshot() { var state = new LinkedHashMap<String,Object>(); for (String table : List.of("api_key", "event", "delivery_task", "delivery_attempt", "endpoint", "audit_log", "simulator_config", "simulator_event_state", "simulator_request_log")) state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1").stream().map(row -> { var copy = new LinkedHashMap<>(row); copy.replaceAll((key,value) -> value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value); return copy; }).toList()); return state; }
        @Override public void close() { client.close(); context.close(); }
    }
}
