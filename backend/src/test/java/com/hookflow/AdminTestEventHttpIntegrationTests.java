package com.hookflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.event.api.EventRequestJsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AdminTestEventHttpIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @Test
    void adminCreatesDistinctEventsWithTheSameInitialTaskRules() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            JsonMapper mapper = context.getBean(JsonMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String base = base(context);
            String token = login(client, mapper, base);
            String businessKey = rotate(client, mapper, base, token);
            long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'admin-test-event-admin'", Long.class);
            EndpointConfiguration endpoint = context.getBean(EndpointSecretProvisioningService.class)
                    .createExternal("admin-test-event-" + UUID.randomUUID(),
                            "https://hooks.acme.com/receive", 6250, true, actor,
                            UUID.randomUUID().toString()).configuration();
            String url = base + "/api/v1/admin/events/test";
            String body = request(endpoint.id(), "{\"z\":[1.0,2],\"a\":true}");
            long beforeEvents = count(jdbc, "event");
            long beforeTasks = count(jdbc, "delivery_task");

            assertError(mapper, post(client, url, null, null, body), 401, "UNAUTHORIZED");
            assertError(mapper, post(client, url, null, businessKey, body), 403, "FORBIDDEN");
            assertEquals(beforeEvents, count(jdbc, "event"));
            assertEquals(beforeTasks, count(jdbc, "delivery_task"));

            HttpResponse<String> first = post(client, url, token, null, body);
            HttpResponse<String> second = post(client, url, token, null, body);
            JsonNode firstBody = assertAccepted(mapper, first, endpoint.id());
            JsonNode secondBody = assertAccepted(mapper, second, endpoint.id());
            String firstId = firstBody.path("eventId").asString();
            String secondId = secondBody.path("eventId").asString();
            assertEquals(4, UUID.fromString(firstId).version());
            assertEquals(firstId, UUID.fromString(firstId).toString());
            assertNotEquals(firstId, secondId);
            assertEquals(beforeEvents + 2, count(jdbc, "event"));
            assertEquals(beforeTasks + 2, count(jdbc, "delivery_task"));

            Long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?",
                    Long.class, firstId);
            assertEquals("{\"a\":true,\"z\":[1,2]}", jdbc.queryForObject(
                    "SELECT payload_json FROM event WHERE id = ?", String.class, eventPk));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(
                    "{\"a\":true,\"z\":[1,2]}".getBytes(StandardCharsets.UTF_8)),
                    jdbc.queryForObject("SELECT payload_fingerprint FROM event WHERE id = ?",
                            (rs, row) -> rs.getBytes(1), eventPk));
            assertEquals(LocalDateTime.ofInstant(
                    Instant.parse(firstBody.path("createdAt").asString()), ZoneOffset.UTC),
                    jdbc.queryForObject("SELECT created_at FROM event WHERE id = ?",
                            (rs, row) -> rs.getObject(1, LocalDateTime.class), eventPk));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE id = ? AND latest_round_no = 1 "
                            + "AND current_status = 'PENDING'", Long.class, eventPk));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ? AND round_no = 1 "
                            + "AND status = 'PENDING' AND attempt_count = 0 AND max_attempts = 3 "
                            + "AND next_run_at = created_at AND updated_at = created_at "
                            + "AND target_type_snapshot = 'EXTERNAL' AND url_snapshot = ? "
                            + "AND timeout_ms_snapshot = 6250 AND retry_delay_1_s = 10 "
                            + "AND retry_delay_2_s = 30",
                    Long.class, eventPk, endpoint.url()));
            byte[] endpointCiphertext = jdbc.queryForObject(
                    "SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?",
                    (rs, row) -> rs.getBytes(1), endpoint.id());
            byte[] taskCiphertext = jdbc.queryForObject(
                    "SELECT secret_ciphertext_snapshot FROM delivery_task WHERE event_pk = ?",
                    (rs, row) -> rs.getBytes(1), eventPk);
            assertArrayEquals(endpointCiphertext, taskCiphertext);
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM delivery_attempt WHERE task_id IN "
                            + "(SELECT id FROM delivery_task WHERE event_pk = ?)", Long.class, eventPk));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE request_id = ?", Long.class,
                    first.headers().firstValue("X-Request-Id").orElseThrow()));
        }
    }

    @Test
    void rejectsInvalidBodyAndUnavailableEndpointWithoutCreatingEvents() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            JsonMapper mapper = context.getBean(JsonMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String base = base(context);
            String token = login(client, mapper, base);
            long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'admin-test-event-admin'", Long.class);
            EndpointConfiguration disabled = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("admin-test-disabled-" + UUID.randomUUID(),
                            5000, false, actor, UUID.randomUUID().toString());
            String url = base + "/api/v1/admin/events/test";
            long beforeEvents = count(jdbc, "event");
            long beforeTasks = count(jdbc, "delivery_task");

            assertError(mapper, post(client, url, token, null, "{}"), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    request(disabled.id(), "null").replace("\"eventType\":\"order.paid\"",
                            "\"eventType\":\" \"")), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    request(disabled.id(), "null").replace("\"payload\":null",
                            "\"payload\":null,\"unexpected\":true")), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    request(disabled.id(), "null").replace("\"payload\":null",
                            "\"payload\":null,\"eventId\":\"caller-id\"")),
                    400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    "{\"eventType\":\"order.paid\",\"endpointId\":0,\"payload\":null}"),
                    400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null, "{broken"),
                    400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    "x".repeat(EventRequestJsonParser.MAX_REQUEST_BYTES + 1)),
                    400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, token, null,
                    request(Long.MAX_VALUE, "null")), 404, "NOT_FOUND");
            assertError(mapper, post(client, url, token, null,
                    request(disabled.id(), "null")), 422, "ENDPOINT_DISABLED");

            assertEquals(beforeEvents, count(jdbc, "event"));
            assertEquals(beforeTasks, count(jdbc, "delivery_task"));
        }
    }

    private static JsonNode assertAccepted(JsonMapper mapper, HttpResponse<String> response,
            long endpointId) throws Exception {
        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(7, body.size());
        assertEquals("order.paid", body.path("eventType").asString());
        assertEquals(endpointId, body.path("endpointId").asLong());
        assertEquals("PENDING", body.path("status").asString());
        assertEquals(1, body.path("latestRoundNo").asInt());
        assertFalse(body.path("duplicate").asBoolean());
        Instant.parse(body.path("createdAt").asString());
        return body;
    }

    private static void assertError(JsonMapper mapper, HttpResponse<String> response,
            int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(3, body.size());
        assertEquals(code, body.path("code").asString());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),
                body.path("requestId").asString());
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String request(long endpointId, String payload) {
        return "{\"eventType\":\"order.paid\",\"endpointId\":"
                + endpointId + ",\"payload\":" + payload + "}";
    }

    private static HttpResponse<String> post(HttpClient client, String url,
            String adminToken, String apiKey, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        if (adminToken != null) {
            builder.header("Authorization", "Bearer " + adminToken);
        }
        if (apiKey != null) {
            builder.header("X-HookFlow-Api-Key", apiKey);
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String login(HttpClient client, JsonMapper mapper, String base) throws Exception {
        HttpResponse<String> response = post(client, base + "/api/v1/auth/login", null, null,
                "{\"username\":\"admin-test-event-admin\","
                        + "\"password\":\"admin-test-event-password\"}");
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("token").asString();
    }

    private static String rotate(HttpClient client, JsonMapper mapper,
            String base, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/admin/api-key/rotate"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("apiKey").asString();
    }

    private static String base(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }

    private static ConfigurableApplicationContext start() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test",
                "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=admin-test-event-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=admin-test-event-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(keyBytes));
    }
}
