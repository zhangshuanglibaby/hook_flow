package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EndpointEnableHttpIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JsonMapper mapper;
    static HttpClient client;
    static String base, token, key;

    @BeforeAll static void start() throws Exception {
        byte[] encryptionKey = new byte[32]; new java.security.SecureRandom().nextBytes(encryptionKey);
        context = new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test", "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=enable-http-admin", "--HOOKFLOW_ADMIN_PASSWORD=enable-http-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(encryptionKey));
        jdbc = context.getBean(JdbcTemplate.class); mapper = context.getBean(JsonMapper.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
        var login = post("/api/v1/auth/login", null, null, "{\"username\":\"enable-http-admin\",\"password\":\"enable-http-password\"}");
        assertEquals(200, login.statusCode()); token = json(login).path("token").asString();
        var rotation = post("/api/v1/admin/api-key/rotate", token, null, null);
        assertEquals(200, rotation.statusCode()); key = json(rotation).path("apiKey").asString();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }

    @Test void actualEnableRestoresSnapshotAndEnforcesIdentityDeduplicationAndSuccessAudit() throws Exception {
        var creation = post("/api/v1/admin/endpoints", token, null,
                "{\"name\":\"enable-http\",\"targetType\":\"EXTERNAL\",\"url\":\"https://hooks.example.com/old\"}");
        assertEquals(201, creation.statusCode()); long ep = json(creation).path("id").asLong();
        String secret = json(creation).path("signingSecret").asString();
        String path = "/api/v1/admin/endpoints/" + ep + "/enable";
        String eventId = "enable-http-" + UUID.randomUUID(); String body = event(eventId, ep, 1);
        assertEquals(202, post("/api/v1/events", null, key, body).statusCode());
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, eventId);
        long task = jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?", Long.class, eventPk);
        var original = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task);
        assertEquals(200, post("/api/v1/admin/endpoints/" + ep + "/disable", token, null, null).statusCode());
        var edit = request("PATCH", "/api/v1/admin/endpoints/" + ep, token, null,
                "{\"url\":\"https://hooks.example.com/new\",\"timeoutMs\":1234}");
        assertEquals(200, edit.statusCode());
        error(post(path, null, null, null), 401, "UNAUTHORIZED");
        error(post(path, null, key, null), 403, "FORBIDDEN");
        for (String invalid : List.of("0", "-1", "abc", "1.2", "9223372036854775808"))
            error(post("/api/v1/admin/endpoints/" + invalid + "/enable", token, null, null), 400, "INVALID_REQUEST");
        error(post("/api/v1/admin/endpoints/9223372036854775807/enable", token, null, null), 404, "NOT_FOUND");
        for (String nonempty : List.of("{}", "null", " ", "unexpected")) error(post(path, token, null, nonempty), 400, "INVALID_REQUEST");
        assertEquals("PAUSED", jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?", String.class, task));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_ENABLED' AND object_id=?", Long.class, Long.toString(ep)));
        Instant before = Instant.now(); var enabled = post(path, token, null, null); Instant after = Instant.now();
        assertEquals(200, enabled.statusCode()); JsonNode view = json(enabled);
        assertTrue(view.path("enabled").asBoolean()); assertEquals(ep, view.path("id").asLong()); assertEquals(8, view.size());
        assertEquals("https://hooks.example.com/new", view.path("url").asString()); assertEquals(1234, view.path("timeoutMs").asInt());
        assertFalse(enabled.body().toLowerCase(Locale.ROOT).contains("secret")); assertFalse(enabled.body().contains(secret));
        byte[] ciphertext = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?", (rs, row) -> rs.getBytes(1), ep);
        assertFalse(enabled.body().contains(Base64.getEncoder().encodeToString(ciphertext)));
        Instant updated = Instant.parse(view.path("updatedAt").asString());
        assertFalse(updated.isBefore(before.minusMillis(1))); assertFalse(updated.isAfter(after)); assertEquals(0, updated.getNano() % 1_000_000);
        String requestId = enabled.headers().firstValue("X-Request-Id").orElseThrow(); UUID.fromString(requestId);
        assertNotEquals("untrusted-client-request-id", requestId);
        var audit = jdbc.queryForMap("SELECT * FROM audit_log WHERE request_id=?", requestId);
        assertEquals("ENDPOINT_ENABLED", audit.get("action")); assertEquals("ENDPOINT", audit.get("object_type"));
        assertEquals(Long.toString(ep), audit.get("object_id"));
        assertEquals(jdbc.queryForObject("SELECT id FROM app_user WHERE username='enable-http-admin'", Long.class), ((Number) audit.get("actor_user_id")).longValue());
        LocalDateTime occurred = jdbc.queryForObject("SELECT occurred_at FROM audit_log WHERE request_id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), requestId);
        assertEquals(updated, occurred.toInstant(ZoneOffset.UTC));
        assertEquals(occurred, jdbc.queryForObject("SELECT updated_at FROM delivery_task WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task));
        assertEquals(occurred, jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task));
        assertFalse(audit.toString().contains(secret)); assertFalse(audit.toString().contains(token)); assertFalse(audit.toString().contains(key));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?", String.class, task));
        assertEquals("PENDING", jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, eventPk));
        assertNull(jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id=?", String.class, task));
        assertNull(jdbc.queryForObject("SELECT resume_due_at FROM delivery_task WHERE id=?", LocalDateTime.class, task));
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?", Integer.class, task));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?", Long.class, task));
        var restored = jdbc.queryForMap("SELECT * FROM delivery_task WHERE id=?", task);
        for (String column : List.of("url_snapshot", "timeout_ms_snapshot", "target_type_snapshot", "max_attempts", "retry_delay_1_s", "retry_delay_2_s"))
            assertEquals(original.get(column), restored.get(column), column);
        assertArrayEquals((byte[]) original.get("secret_ciphertext_snapshot"), (byte[]) restored.get("secret_ciphertext_snapshot"));
        assertArrayEquals(ciphertext, (byte[]) restored.get("secret_ciphertext_snapshot"));
        var repeated = post(path, token, null, null); assertEquals(200, repeated.statusCode()); assertEquals(view, json(repeated));
        assertEquals(occurred, jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task));
        assertEquals(occurred, jdbc.queryForObject("SELECT updated_at FROM delivery_task WHERE id=?", (rs, row) -> rs.getObject(1, LocalDateTime.class), task));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_ENABLED' AND object_id=?", Long.class, Long.toString(ep)));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?", Long.class, repeated.headers().firstValue("X-Request-Id").orElseThrow()));
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class), tasks = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class);
        var duplicate = post("/api/v1/events", null, key, body); assertEquals(200, duplicate.statusCode());
        assertTrue(json(duplicate).path("duplicate").asBoolean()); assertEquals("PENDING", json(duplicate).path("status").asString());
        error(post("/api/v1/events", null, key, event(eventId, ep, 2)), 409, "CONFLICT");
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class)); assertEquals(tasks, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class));
        var fresh = post("/api/v1/events", null, key, event("new-" + UUID.randomUUID(), ep, 1)); assertEquals(202, fresh.statusCode());
        assertFalse(json(fresh).path("duplicate").asBoolean());
        assertEquals(events + 1, jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class)); assertEquals(tasks + 1, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class));
        assertEquals("https://hooks.example.com/new", jdbc.queryForObject("SELECT url_snapshot FROM delivery_task WHERE id<>? AND event_pk IN (SELECT id FROM event WHERE endpoint_id=?)", String.class, task, ep));
    }

    @Test void realAuditDatabaseFailureReturnsSafe500AndRollsBackAllResumedRows() throws Exception {
        var creation = post("/api/v1/admin/endpoints", token, null, "{\"name\":\"enable-rollback\",\"targetType\":\"SIMULATOR\"}");
        assertEquals(201, creation.statusCode()); long ep = json(creation).path("id").asLong();
        String eventId = "rollback-" + UUID.randomUUID();
        assertEquals(202, post("/api/v1/events", null, key, event(eventId, ep, 1)).statusCode());
        assertEquals(200, post("/api/v1/admin/endpoints/" + ep + "/disable", token, null, null).statusCode());
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id=?", Long.class, eventId);
        var endpointBefore = jdbc.queryForMap("SELECT enabled,updated_at FROM endpoint WHERE id=?", ep);
        var taskBefore = jdbc.queryForMap("SELECT status,next_run_at,resume_status,resume_due_at,updated_at,attempt_count FROM delivery_task WHERE event_pk=?", eventPk);
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT ck_enable_http_audit CHECK (action <> 'ENDPOINT_ENABLED' OR object_id <> '" + ep + "')");
        try {
            var failure = post("/api/v1/admin/endpoints/" + ep + "/enable", token, null, null); error(failure, 500, "INTERNAL_ERROR");
            assertFalse(failure.body().contains("ck_enable_http_audit")); assertFalse(failure.body().contains("SQLException"));
            assertEquals(endpointBefore, jdbc.queryForMap("SELECT enabled,updated_at FROM endpoint WHERE id=?", ep));
            assertEquals(taskBefore, jdbc.queryForMap("SELECT status,next_run_at,resume_status,resume_due_at,updated_at,attempt_count FROM delivery_task WHERE event_pk=?", eventPk));
            assertEquals("PAUSED", jdbc.queryForObject("SELECT current_status FROM event WHERE id=?", String.class, eventPk));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt a JOIN delivery_task t ON t.id=a.task_id WHERE t.event_pk=?", Long.class, eventPk));
        } finally { jdbc.execute("ALTER TABLE audit_log DROP CHECK ck_enable_http_audit"); }
    }
    static String event(String id, long ep, int value) { return "{\"eventId\":\"" + id + "\",\"eventType\":\"order.paid\",\"endpointId\":" + ep + ",\"payload\":{\"value\":" + value + "}}"; }
    static JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    static void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); JsonNode body = json(response);
        assertEquals(code, body.path("code").asString()); assertEquals(3, body.size());
        String id = response.headers().firstValue("X-Request-Id").orElseThrow(); assertEquals(id, body.path("requestId").asString());
        assertNotEquals("untrusted-client-request-id", id); assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?", Long.class, id));
    }
    static HttpResponse<String> post(String path, String admin, String business, String body) throws Exception { return request("POST", path, admin, business, body); }
    static HttpResponse<String> request(String method, String path, String admin, String business, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("X-Request-Id", "untrusted-client-request-id");
        if (admin != null) request.header("Authorization", "Bearer " + admin);
        if (business != null) request.header("X-HookFlow-Api-Key", business);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
