package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.delivery.application.*;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class DeliveryRedeliveryHttpIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JsonMapper mapper;
    static HttpClient client;
    static String base, token, key;
    static long actor;

    @BeforeAll static void start() throws Exception {
        byte[] encryption = new byte[32]; new java.security.SecureRandom().nextBytes(encryption);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=redelivery-http", "--HOOKFLOW_ADMIN_PASSWORD=redelivery-http-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(encryption));
        jdbc = context.getBean(JdbcTemplate.class); mapper = context.getBean(JsonMapper.class);
        actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='redelivery-http'", Long.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
        var login = post("/api/v1/auth/login", null, null, "{\"username\":\"redelivery-http\",\"password\":\"redelivery-http-password\"}");
        assertEquals(200, login.statusCode()); token = json(login).path("token").asString();
        var rotate = post("/api/v1/admin/api-key/rotate", token, null, null);
        assertEquals(200, rotate.statusCode()); key = json(rotate).path("apiKey").asString();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    static String id() { return UUID.randomUUID().toString(); }
    static String path(String eventId) { return "/api/v1/admin/events/" + URLEncoder.encode(eventId, StandardCharsets.UTF_8).replace("+", "%20") + "/redeliver"; }
    static Fixture create(boolean failed) throws Exception {
        var response = post("/api/v1/admin/endpoints", token, null,
                "{\"name\":\"redelivery-http\",\"targetType\":\"EXTERNAL\",\"url\":\"https://hooks.example.com/old\"}");
        assertEquals(201, response.statusCode()); long ep = json(response).path("id").asLong();
        String external = "订单-redelivery-" + id(); String body = event(external, ep, 1);
        assertEquals(202, post("/api/v1/events", null, key, body).statusCode());
        long pk = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, external);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?", Long.class, pk);
        if (failed) {
            var claim = context.getBean(DeliveryClaimService.class).claim(task).orElseThrow();
            assertTrue(context.getBean(DeliveryCompletionService.class).complete(task, 1, claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(404, "controlled-http-failure")));
        }
        return new Fixture(ep, pk, task, external, body, json(response).path("signingSecret").asString());
    }

    @Test void officialApiCreatesNewSummaryAndPreservesIdentityHistoryDeduplicationAndAudit() throws Exception {
        Fixture f = create(true); var old = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task);
        var history = jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=?", f.task);
        var eventBefore = jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.pk);
        var edit = request("PATCH", "/api/v1/admin/endpoints/" + f.ep, token, null, "{\"url\":\"https://hooks.example.com/new\",\"timeoutMs\":1234}");
        assertEquals(200, edit.statusCode());
        Instant before = Instant.now(); var success = post(path(f.external), token, null, null); Instant after = Instant.now();
        assertEquals(202, success.statusCode(), success.body()); JsonNode view = json(success);
        assertEquals(10, view.size()); assertEquals(f.external, view.path("eventId").asString()); assertEquals("order.paid", view.path("eventType").asString());
        assertEquals(f.ep, view.path("endpointId").asLong()); assertEquals(2, view.path("roundNo").asInt()); assertEquals("PENDING", view.path("status").asString());
        assertEquals(0, view.path("attemptCount").asInt()); assertEquals(3, view.path("maxAttempts").asInt()); assertTrue(view.path("lastAttemptAt").isNull());
        long task = view.path("taskId").asLong(); assertNotEquals(f.task, task);
        Instant created = Instant.parse(view.path("createdAt").asString()); assertTrue(view.path("createdAt").asString().endsWith("Z"));
        assertFalse(created.isBefore(before.minusMillis(1))); assertFalse(created.isAfter(after)); assertEquals(0, created.getNano() % 1_000_000);
        String requestId = success.headers().firstValue("X-Request-Id").orElseThrow(); UUID.fromString(requestId); assertNotEquals("untrusted-client-request-id", requestId);
        var audit = jdbc.queryForMap("SELECT * FROM audit_log WHERE request_id=?", requestId);
        assertEquals("EVENT_REDELIVERED", audit.get("action")); assertEquals("EVENT", audit.get("object_type")); assertEquals(Long.toString(f.pk), audit.get("object_id"));
        assertEquals(actor, ((Number) audit.get("actor_user_id")).longValue());
        LocalDateTime occurred = jdbc.queryForObject("SELECT occurred_at FROM audit_log WHERE request_id=?", (rs, n) -> rs.getObject(1, LocalDateTime.class), requestId);
        assertEquals(created, occurred.toInstant(ZoneOffset.UTC));
        for (String column : List.of("created_at", "updated_at", "next_run_at")) assertEquals(occurred, jdbc.queryForObject("SELECT " + column + " FROM delivery_task WHERE id=?", (rs, n) -> rs.getObject(1, LocalDateTime.class), task));
        var fresh = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task);
        assertEquals("https://hooks.example.com/new", fresh.get("url_snapshot")); assertEquals(1234, fresh.get("timeout_ms_snapshot"));
        byte[] ciphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs, n) -> rs.getBytes(1), f.ep);
        assertArrayEquals(ciphertext, (byte[]) fresh.get("secret_ciphertext_snapshot"));
        for (String forbidden : List.of(f.secret, token, key, Base64.getEncoder().encodeToString(ciphertext), "secret", "payload")) {
            assertFalse(success.body().contains(forbidden)); assertFalse(audit.toString().contains(forbidden));
        }
        DeliveryRedeliveryIntegrationTests.same(old, jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task));
        assertEquals(history, jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=?", f.task));
        var current = jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.pk);
        for (String column : List.of("id", "event_id", "event_type", "endpoint_id", "payload_json", "created_at")) assertEquals(eventBefore.get(column), current.get(column));
        assertArrayEquals((byte[]) eventBefore.get("payload_fingerprint"), (byte[]) current.get("payload_fingerprint"));
        assertEquals(2, current.get("latest_round_no")); assertEquals("PENDING", current.get("current_status")); assertNull(current.get("terminal_at"));
        error(post(path(f.external), token, null, null), 409, "CONFLICT");
        long eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class), taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class);
        var duplicate = post("/api/v1/events", null, key, f.body); assertEquals(200, duplicate.statusCode());
        assertTrue(json(duplicate).path("duplicate").asBoolean()); assertEquals(2, json(duplicate).path("latestRoundNo").asInt()); assertEquals("PENDING", json(duplicate).path("status").asString());
        error(post("/api/v1/events", null, key, event(f.external, f.ep, 2)), 409, "CONFLICT");
        assertEquals(eventCount, jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class)); assertEquals(taskCount, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk=?", Long.class, f.pk));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EVENT_REDELIVERED' AND object_id=?", Long.class, Long.toString(f.pk)));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?", Long.class, task));
    }

    @Test void identityParametersStateDisabledPriorityAndNotFoundUseExistingSafeErrors() throws Exception {
        Fixture f = create(true), pending = create(false);
        error(post(path(f.external), null, null, null), 401, "UNAUTHORIZED");
        error(post(path(f.external), null, key, null), 403, "FORBIDDEN");
        error(post(path(pending.external), token, null, null), 409, "CONFLICT");
        error(post(path("not-a-number-or-uuid"), token, null, null), 404, "NOT_FOUND");
        for (String invalid : List.of(" ", "x".repeat(129), "😀".repeat(129))) error(post(path(invalid), token, null, null), 400, "INVALID_REQUEST");
        error(post(path("😀".repeat(128)), token, null, null), 404, "NOT_FOUND");
        for (String body : List.of("{}", "null", " ", "unexpected")) error(post(path(f.external), token, null, body), 400, "INVALID_REQUEST");
        assertEquals(200, post("/api/v1/admin/endpoints/" + f.ep + "/disable", token, null, null).statusCode());
        error(post(path(f.external), token, null, null), 422, "ENDPOINT_DISABLED");
        assertEquals(200, post("/api/v1/admin/endpoints/" + pending.ep + "/disable", token, null, null).statusCode());
        error(post(path(pending.external), token, null, null), 422, "ENDPOINT_DISABLED");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk IN (?,?) AND round_no>1", Long.class, f.pk, pending.pk));
    }

    @Test void actualAuditDatabaseFailureReturnsSafe500AndRollsBackAllBusinessChanges() throws Exception {
        Fixture f = create(true); var event = jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.pk);
        var task = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task);
        var history = jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=?", f.task);
        var logger = (Logger) LoggerFactory.getLogger(com.hookflow.common.api.ApiExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT ck_redelivery_http_audit CHECK (action <> 'EVENT_REDELIVERED')");
        try {
            var failure = post(path(f.external), token, null, null); error(failure, 500, "INTERNAL_ERROR");
            assertFalse(failure.body().contains("SQLException"));
            for (String forbidden : List.of("ck_redelivery_http_audit", "INSERT INTO", token, key, f.secret)) {
                assertFalse(failure.body().contains(forbidden));
                for (var log : appender.list) { assertFalse(log.getFormattedMessage().contains(forbidden)); assertNull(log.getThrowableProxy()); }
            }
            assertFalse(appender.list.isEmpty());
            assertEquals(failure.headers().firstValue("X-Request-Id").orElseThrow(), appender.list.getLast().getMDCPropertyMap().get("requestId"));
            DeliveryRedeliveryIntegrationTests.same(event, jdbc.queryForMap("SELECT * FROM event WHERE id=?", f.pk));
            DeliveryRedeliveryIntegrationTests.same(task, jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", f.task));
            assertEquals(history, jdbc.queryForList("SELECT * FROM delivery_attempt WHERE task_id=?", f.task));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE event_pk=?", Long.class, f.pk));
        } finally { jdbc.execute("ALTER TABLE audit_log DROP CHECK ck_redelivery_http_audit"); logger.detachAppender(appender); appender.stop(); }
        assertEquals(202, post(path(f.external), token, null, null).statusCode());
    }

    static String event(String id, long ep, int value) { return mapper.writeValueAsString(Map.of("eventId", id, "eventType", "order.paid", "endpointId", ep, "payload", Map.of("value", value))); }
    static JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    static void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); var node = json(response);
        assertEquals(code, node.path("code").asString()); assertEquals(3, node.size());
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(requestId, node.path("requestId").asString());
        assertNotEquals("untrusted-client-request-id", requestId); assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?", Long.class, requestId));
    }
    static HttpResponse<String> post(String path, String admin, String business, String body) throws Exception { return request("POST", path, admin, business, body); }
    static HttpResponse<String> request(String method, String path, String admin, String business, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("X-Request-Id", "untrusted-client-request-id");
        if (admin != null) request.header("Authorization", "Bearer " + admin);
        if (business != null) request.header("X-HookFlow-Api-Key", business);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    record Fixture(long ep, long pk, long task, String external, String body, String secret) { }
}
