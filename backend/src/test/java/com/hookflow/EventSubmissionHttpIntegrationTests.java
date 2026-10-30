package com.hookflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.event.api.EventRequestJsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class EventSubmissionHttpIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @Test
    void businessKeyCreatesEventAndInitialTaskOnlyAfterCommit() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            JsonMapper mapper = context.getBean(JsonMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String base = base(context);
            String adminToken = login(client, mapper, base);
            String key = rotate(client, mapper, base, adminToken);
            Long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'event-http-test-admin'", Long.class);
            EndpointConfiguration endpoint = context.getBean(EndpointSecretProvisioningService.class)
                    .createExternal("event-http-external-" + UUID.randomUUID(),
                            "https://hooks.acme.com/receive", 6250, true, actor,
                            UUID.randomUUID().toString()).configuration();
            String eventId = "event-http-" + UUID.randomUUID();
            String body = request(eventId, endpoint.id(), "{\"z\":[1.0,2],\"a\":true}");

            HttpResponse<String> anonymous = post(client, base + "/api/v1/events", null, null, body);
            assertError(mapper, anonymous, 401, "UNAUTHORIZED");
            HttpResponse<String> adminOnly = post(client, base + "/api/v1/events", adminToken, null, body);
            assertError(mapper, adminOnly, 403, "FORBIDDEN");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE event_id = ?", Long.class, eventId));

            HttpResponse<String> accepted = post(client, base + "/api/v1/events", null, key, body);
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertTrue(accepted.headers().firstValue("X-Request-Id").isPresent());
            JsonNode response = mapper.readTree(accepted.body());
            assertEquals(7, response.size());
            assertEquals(eventId, response.path("eventId").asString());
            assertEquals("order.paid", response.path("eventType").asString());
            assertEquals(endpoint.id(), response.path("endpointId").asLong());
            assertEquals("PENDING", response.path("status").asString());
            assertEquals(1, response.path("latestRoundNo").asInt());
            assertFalse(response.path("duplicate").asBoolean());
            Instant createdAt = Instant.parse(response.path("createdAt").asString());
            Long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?",
                    Long.class, eventId);
            assertEquals("{\"a\":true,\"z\":[1,2]}", jdbc.queryForObject(
                    "SELECT payload_json FROM event WHERE id = ?", String.class, eventPk));
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(
                    "{\"a\":true,\"z\":[1,2]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    jdbc.queryForObject("SELECT payload_fingerprint FROM event WHERE id = ?",
                            (rs, row) -> rs.getBytes(1), eventPk));
            assertEquals(LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC), jdbc.queryForObject(
                    "SELECT created_at FROM event WHERE id = ?",
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
        }
    }

    @Test
    void rejectsBadBodyMissingAndDisabledEndpointWithoutNewRows() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            JsonMapper mapper = context.getBean(JsonMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String base = base(context);
            String adminToken = login(client, mapper, base);
            String key = rotate(client, mapper, base, adminToken);
            Long actor = jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE username = 'event-http-test-admin'", Long.class);
            EndpointConfiguration disabled = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("event-http-disabled-" + UUID.randomUUID(),
                            5000, false, actor, UUID.randomUUID().toString());
            String eventId = "event-http-rejected-" + UUID.randomUUID();
            String url = base + "/api/v1/events";

            assertError(mapper, post(client, url, null, key, "{}"), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, null, key,
                    request(eventId, disabled.id(), "null").replace("\"eventType\":\"order.paid\"",
                            "\"eventType\":\" \"")), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, null, key,
                    request(eventId, disabled.id(), "null").replace("\"payload\":null",
                            "\"payload\":null,\"unexpected\":true")), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, null, key, "{broken"), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, null, key,
                    "x".repeat(EventRequestJsonParser.MAX_REQUEST_BYTES + 1)), 400, "INVALID_REQUEST");
            assertError(mapper, post(client, url, null, key,
                    request(eventId, Long.MAX_VALUE, "null")), 404, "NOT_FOUND");
            assertError(mapper, post(client, url, null, key,
                    request(eventId, disabled.id(), "null")), 422, "ENDPOINT_DISABLED");

            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class));
        }
    }

    @Test
    void exactUtf8LimitAndUnicodeNumericIdentityAreEnforcedOverFormalHttp() throws Exception {
        try (var context = start(); var client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()) {
            var mapper = context.getBean(JsonMapper.class); var jdbc = context.getBean(JdbcTemplate.class);
            String base = base(context); String key = rotate(client, mapper, base, login(client, mapper, base));
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='event-http-test-admin'", Long.class);
            long endpoint = context.getBean(EndpointSecretProvisioningService.class).createSimulator("byte-limit", 5000, actor, UUID.randomUUID().toString()).id();
            String id = "limit-" + UUID.randomUUID();
            String empty = request(id, endpoint, "\"\"");
            int remaining = EventRequestJsonParser.MAX_REQUEST_BYTES - empty.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String body = request(id, endpoint, "\"" + "中".repeat(remaining / 3) + "x".repeat(remaining % 3) + "\"");
            assertEquals(262144, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            String url = base + "/api/v1/events";
            assertEquals(202, post(client, url, null, key, body).statusCode());
            assertEquals(200, post(client, url, null, key, body).statusCode());
            long before = jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class);
            assertError(mapper, post(client, url, null, key, body + " "), 400, "INVALID_REQUEST");
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM event", Long.class));
            String identity = " 原值-é-" + UUID.randomUUID() + " ";
            String exact = "{\"v\":123456789012345678901234567890.100000000000000001}";
            assertEquals(202, post(client, url, null, key, request(identity, endpoint, exact)).statusCode());
            assertEquals(200, post(client, url, null, key, request(identity, endpoint, "{\"v\":123456789012345678901234567890.1000000000000000010}")).statusCode());
            assertError(mapper, post(client, url, null, key, request(identity, endpoint, "{\"v\":123456789012345678901234567890.100000000000000002}")), 409, "CONFLICT");
            for (String distinct : java.util.List.of(identity.strip(), identity.replace("é", "é"), identity.replace("原值", "原値")))
                assertEquals(202, post(client, url, null, key, request(distinct, endpoint, exact)).statusCode());
            assertEquals(identity, jdbc.queryForObject("SELECT event_id FROM event WHERE event_id=?", String.class, identity));
        }
    }

    private static String request(String eventId, long endpointId, String payload) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"order.paid\","
                + "\"endpointId\":" + endpointId + ",\"payload\":" + payload + "}";
    }

    private static HttpResponse<String> post(HttpClient client, String url,
            String adminToken, String apiKey, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(8))
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
                "{\"username\":\"event-http-test-admin\",\"password\":\"event-http-test-password\"}");
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body()).path("token").asString();
    }

    private static String rotate(HttpClient client, JsonMapper mapper,
            String base, String adminToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/api-key/rotate"))
                .header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body()).path("apiKey").asString();
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
                "--HOOKFLOW_ADMIN_USERNAME=event-http-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=event-http-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(keyBytes));
    }
}
