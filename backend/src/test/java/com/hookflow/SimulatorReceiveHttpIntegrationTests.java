package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.config.RequestIdFilter;
import com.hookflow.delivery.application.DeliveryClaimService;
import com.hookflow.delivery.application.DeliveryCompletionService;
import com.hookflow.delivery.application.DeliveryRedeliveryService;
import com.hookflow.delivery.application.DueDeliveryScanner;
import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.simulator.api.SimulatorReceiveController;
import com.hookflow.simulator.api.SimulatorVerifiedReception;
import com.hookflow.support.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class SimulatorReceiveHttpIntegrationTests {
    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");
    private static final byte[] BODY = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
    private static final String[] HEADER_NAMES = {
            "X-HookFlow-Event-Id", "X-HookFlow-Timestamp", "X-HookFlow-Signature"};

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(NOW); }
        @Bean @Primary ObservedReception observedReception(
                com.hookflow.simulator.application.SimulatorProcessingService processing,
                com.hookflow.simulator.application.SimulatorResponseDelay delay) {
            return new ObservedReception(processing, delay);
        }
    }

    static class ObservedReception extends SimulatorVerifiedReception {
        final AtomicInteger count = new AtomicInteger();
        final ArrayBlockingQueue<Receipt> receipts = new ArrayBlockingQueue<>(32);
        ObservedReception(com.hookflow.simulator.application.SimulatorProcessingService processing,
                com.hookflow.simulator.application.SimulatorResponseDelay delay) { super(processing, delay); }
        @Override public com.hookflow.simulator.domain.SimulatorConfiguration.Decision acknowledge(String eventId) {
            var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            count.incrementAndGet();
            assertTrue(receipts.offer(new Receipt(request.getMethod(), request.getRequestURI(),
                    request.getHeader(HEADER_NAMES[0]), request.getLocalPort(),
                    (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                    TransactionSynchronizationManager.isActualTransactionActive())));
            return super.acknowledge(eventId);
        }
    }

    record Receipt(String method, String path, String eventId, int port, String requestId, boolean inTransaction) { }

    @AfterEach
    void clearFixtures() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        for (String table : List.of("simulator_request_log", "simulator_event_state", "simulator_config",
                "delivery_attempt", "delivery_task", "event", "audit_log", "endpoint")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void validSignatureProcessesOnceAndTamperingCannotEnterReception() throws Exception {
        try (Fixture f = new Fixture()) {
            long endpoint = f.endpoint("SIMULATOR");
            String eventId = f.event(endpoint);
            String secret = f.secret(eventId);
            String state = f.databaseFingerprint();
            HttpResponse<String> accepted = f.signed(eventId, NOW.toString(), BODY, secret);
            assertEquals(200, accepted.statusCode(), accepted.body());
            JsonNode body = f.mapper.readTree(accepted.body());
            assertEquals(2, body.size());
            assertTrue(body.path("signatureVerified").asBoolean());
            assertTrue(body.path("businessProcessed").asBoolean());
            assertEquals(1, f.reception.count.get());
            assertEquals(state, f.databaseFingerprint());
            assertEquals(1L, f.count("simulator_event_state"));
            assertEquals(1L, f.count("simulator_request_log"));

            Map<String, String> headers = f.headers(eventId, NOW.toString(), BODY, secret);
            for (byte[] changed : List.of("{ \"amount\":1}".getBytes(StandardCharsets.UTF_8),
                    "{\"amount\":1.0}".getBytes(StandardCharsets.UTF_8),
                    "{\"amount\":2}".getBytes(StandardCharsets.UTF_8))) {
                f.error(f.receive(headers, changed), 401, "UNAUTHORIZED");
            }
            var changedId = new LinkedHashMap<>(headers);
            changedId.put(HEADER_NAMES[0], f.event(endpoint));
            state = f.databaseFingerprint();
            f.error(f.receive(changedId, BODY), 401, "UNAUTHORIZED");
            var changedTime = new LinkedHashMap<>(headers);
            changedTime.put(HEADER_NAMES[1], NOW.plusSeconds(1).toString());
            f.error(f.receive(changedTime, BODY), 401, "UNAUTHORIZED");
            assertEquals(1, f.reception.count.get());
            assertEquals(state, f.databaseFingerprint());
        }
    }

    @Test
    void malformedMissingDuplicateAndOversizedHeadersRejectThroughRealSecurityControllerChain() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.event(f.endpoint("SIMULATOR"));
            var valid = f.headers(id, NOW.toString(), BODY, f.secret(id));
            String before = f.databaseFingerprint();
            for (String name : HEADER_NAMES) {
                var missing = new LinkedHashMap<>(valid);
                missing.remove(name);
                f.error(f.receive(missing, BODY), 401, "UNAUTHORIZED");
                for (String invalid : List.of(" ", "x".repeat(300))) {
                    var malformed = new LinkedHashMap<>(valid);
                    malformed.put(name, invalid);
                    f.error(f.receive(malformed, BODY), 401, "UNAUTHORIZED");
                }
                var duplicate = f.request(valid, BODY).header(name, valid.get(name)).build();
                f.error(f.send(duplicate), 401, "UNAUTHORIZED");
            }
            for (String signature : List.of("0".repeat(63), "0".repeat(65), "A".repeat(64),
                    "g".repeat(64), "0x" + "0".repeat(64), "0".repeat(64) + "," + "0".repeat(64))) {
                var malformed = new LinkedHashMap<>(valid);
                malformed.put(HEADER_NAMES[2], signature);
                f.error(f.receive(malformed, BODY), 401, "UNAUTHORIZED");
            }
            f.error(f.receive(Map.of("Authorization", "Bearer " + f.token), BODY), 401, "UNAUTHORIZED");
            f.error(f.receive(Map.of("X-HookFlow-Api-Key", f.apiKey), BODY), 401, "UNAUTHORIZED");
            var privileged = new LinkedHashMap<>(valid);
            privileged.put(HEADER_NAMES[2], "0".repeat(64));
            privileged.put("Authorization", "Bearer " + f.token);
            privileged.put("X-HookFlow-Api-Key", f.apiKey);
            f.error(f.receive(privileged, BODY), 401, "UNAUTHORIZED");
            for (String path : List.of("/internal/other", SimulatorInternalTargetRoute.PATH + "/extra",
                    "/internal/hookflow/simulator/config")) {
                f.error(f.send(HttpRequest.newBuilder(URI.create(f.base + path)).timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(BODY)).build()), 401, "UNAUTHORIZED");
            }
            f.error(f.send(HttpRequest.newBuilder(URI.create(f.base + SimulatorInternalTargetRoute.PATH))
                    .timeout(Duration.ofSeconds(10)).GET().build()), 401, "UNAUTHORIZED");
            assertEquals(0, f.reception.count.get());
            assertEquals(before, f.databaseFingerprint());
        }
    }

    @Test
    void windowAndBodyLimitsAreEnforcedWithInjectedClockAndChunkedRawBytes() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.event(f.endpoint("SIMULATOR"));
            String secret = f.secret(id);
            String before = f.databaseFingerprint();
            for (Instant timestamp : List.of(NOW.minusSeconds(300), NOW.plusSeconds(300))) {
                assertEquals(200, f.signed(id, timestamp.toString(), BODY, secret).statusCode());
            }
            for (Instant timestamp : List.of(NOW.minusSeconds(300).minusNanos(1), NOW.plusSeconds(300).plusNanos(1))) {
                f.error(f.signed(id, timestamp.toString(), BODY, secret), 401, "UNAUTHORIZED");
            }
            for (String timestamp : List.of("1761782400", "not-time", "2026-10-30T01:00:00+00:00",
                    "2026-10-30T01:00:00.0Z")) {
                f.error(f.signed(id, timestamp, BODY, secret), 401, "UNAUTHORIZED");
            }
            byte[] exact = ("\"" + "a".repeat(SimulatorReceiveController.MAX_BODY_BYTES - 2) + "\"")
                    .getBytes(StandardCharsets.UTF_8);
            assertEquals(200, f.signed(id, NOW.toString(), exact, secret).statusCode());
            byte[] oversized = java.util.Arrays.copyOf(exact, exact.length + 1);
            var headers = f.headers(id, NOW.toString(), oversized, secret);
            HttpRequest.Builder chunked = HttpRequest.newBuilder(URI.create(f.base + SimulatorInternalTargetRoute.PATH))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized)));
            headers.forEach(chunked::header);
            f.error(f.send(chunked.build()), 400, "INVALID_REQUEST");
            f.error(f.signed(id, NOW.toString(), new byte[0], secret), 400, "INVALID_REQUEST");
            assertEquals(3, f.reception.count.get());
            assertEquals(before, f.databaseFingerprint());
        }
    }

    @Test
    void unknownWrongAssociationExternalTargetAndCorruptSnapshotFailClosedWithoutLeaks() throws Exception {
        try (Fixture f = new Fixture()) {
            String simulator = f.event(f.endpoint("SIMULATOR"));
            String other = f.event(f.endpoint("SIMULATOR"));
            String external = f.event(f.endpoint("EXTERNAL"));
            String secret = f.secret(simulator);
            String before = f.databaseFingerprint();
            f.error(f.signed("unknown-" + UUID.randomUUID(), NOW.toString(), BODY, secret), 401, "UNAUTHORIZED");
            f.error(f.signed(simulator, NOW.toString(), BODY, f.secret(other)), 401, "UNAUTHORIZED");
            f.error(f.signed(external, NOW.toString(), BODY, f.secret(external)), 401, "UNAUTHORIZED");
            assertEquals(before, f.databaseFingerprint());

            // 仅在测试库构造损坏密文及错误关联，不新增正式密钥轮换或弱化 schema。
            f.jdbc.update("UPDATE delivery_task SET secret_ciphertext_snapshot = ? WHERE event_pk = "
                    + "(SELECT id FROM event WHERE event_id = ?)", new byte[] {1, 2, 3}, simulator);
            before = f.databaseFingerprint();
            Logger logger = (Logger) LoggerFactory.getLogger("com.hookflow");
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.setContext(logger.getLoggerContext()); logs.start(); logger.addAppender(logs);
            try {
                f.error(f.signed(simulator, NOW.toString(), BODY, secret), 401, "UNAUTHORIZED");
                for (ILoggingEvent log : logs.list) {
                    assertNull(log.getThrowableProxy());
                    assertFalse(log.getFormattedMessage().contains(secret));
                    assertFalse(log.getFormattedMessage().contains(f.token));
                    assertFalse(log.getFormattedMessage().contains(f.apiKey));
                    assertFalse(log.getFormattedMessage().contains(new String(BODY, StandardCharsets.UTF_8)));
                }
            } finally { logger.detachAppender(logs); logs.stop(); }
            assertEquals(before, f.databaseFingerprint());
            f.jdbc.update("UPDATE event SET endpoint_id = (SELECT endpoint_id FROM (SELECT endpoint_id "
                    + "FROM event WHERE event_id = ?) x) WHERE event_id = ?", external, other);
            before = f.databaseFingerprint();
            f.error(f.signed(other, NOW.toString(), BODY, f.secret(other)), 401, "UNAUTHORIZED");
            assertEquals(before, f.databaseFingerprint());
            assertEquals(0, f.reception.count.get());
        }
    }

    @Test
    void oldAndNewRoundsUseTheirOwnSnapshotsNotCurrentEndpointSecretOrCallerRound() throws Exception {
        try (Fixture f = new Fixture()) {
            long endpoint = f.endpoint("SIMULATOR");
            String id = f.event(endpoint);
            String original = f.secret(id);
            long task = f.task(id);
            var claim = f.context.getBean(DeliveryClaimService.class).claim(task).orElseThrow();
            assertTrue(f.context.getBean(DeliveryCompletionService.class).complete(task, claim.attemptNo(),
                    claim.claimToken(), SingleDeliveryHttpResult.fromHttpResponse(404, "")));
            var cipher = f.context.getBean(EndpointSigningSecretCipher.class);
            String replacement = cipher.generateSecret();
            f.jdbc.update("UPDATE endpoint SET signing_secret_ciphertext = ? WHERE id = ?", cipher.encrypt(replacement), endpoint);
            long actor = f.jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'p801-admin'", Long.class);
            f.context.getBean(DeliveryRedeliveryService.class).redeliver(id, actor, UUID.randomUUID().toString());
            assertEquals(replacement, f.secret(id));
            String before = f.databaseFingerprint();
            for (String secret : List.of(original, replacement)) {
                var headers = f.headers(id, NOW.toString(), BODY, secret);
                headers.put("X-HookFlow-Round", "999999");
                headers.put("X-HookFlow-Endpoint-Id", "999999");
                headers.put("X-HookFlow-Signing-Secret", "caller-cannot-select-secret");
                assertEquals(200, f.receive(headers, BODY).statusCode());
            }
            assertEquals(before, f.databaseFingerprint());
            f.jdbc.update("UPDATE endpoint SET signing_secret_ciphertext = ? WHERE id = ?", new byte[] {0}, endpoint);
            before = f.databaseFingerprint();
            assertEquals(200, f.signed(id, NOW.toString(), BODY, original).statusCode());
            assertEquals(200, f.signed(id, NOW.toString(), BODY, replacement).statusCode());
            assertEquals(before, f.databaseFingerprint());
        }
    }

    @Test
    void missingTaskWrongSnapshotTypeAndCorruptHistoricalCiphertextDoNotFallBack() throws Exception {
        try (Fixture f = new Fixture()) {
            long endpoint = f.endpoint("SIMULATOR");
            String id = f.event(endpoint);
            String secret = f.secret(id);
            long task = f.task(id);
            f.jdbc.update("UPDATE delivery_task SET target_type_snapshot = 'EXTERNAL', "
                    + "url_snapshot = 'https://hooks.example.com/receive' WHERE id = ?", task);
            String before = f.databaseFingerprint();
            f.error(f.signed(id, NOW.toString(), BODY, secret), 401, "UNAUTHORIZED");
            assertEquals(before, f.databaseFingerprint());
            f.jdbc.update("DELETE FROM delivery_task WHERE id = ?", task);
            before = f.databaseFingerprint();
            f.error(f.signed(id, NOW.toString(), BODY, secret), 401, "UNAUTHORIZED");
            assertEquals(before, f.databaseFingerprint());

            String roundsId = f.event(endpoint);
            String original = f.secret(roundsId);
            var claims = f.context.getBean(DeliveryClaimService.class);
            var completions = f.context.getBean(DeliveryCompletionService.class);
            var redelivery = f.context.getBean(DeliveryRedeliveryService.class);
            long actor = f.jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'p801-admin'", Long.class);
            for (int round = 1; round < 18; round++) {
                long current = f.task(roundsId);
                var claim = claims.claim(current).orElseThrow();
                assertTrue(completions.complete(current, claim.attemptNo(), claim.claimToken(),
                        SingleDeliveryHttpResult.fromHttpResponse(404, "")));
                redelivery.redeliver(roundsId, actor, UUID.randomUUID().toString());
            }
            before = f.databaseFingerprint();
            assertEquals(200, f.signed(roundsId, NOW.toString(), BODY, original).statusCode());
            assertEquals(before, f.databaseFingerprint());
            f.jdbc.update("UPDATE delivery_task SET secret_ciphertext_snapshot = ? WHERE event_pk = "
                    + "(SELECT id FROM event WHERE event_id = ?) AND round_no = 17", new byte[] {0}, roundsId);
            before = f.databaseFingerprint();
            f.error(f.signed(roundsId, NOW.toString(), BODY, original), 401, "UNAUTHORIZED");
            assertEquals(before, f.databaseFingerprint());
            assertEquals(1, f.reception.count.get());
        }
    }

    @Test
    void formalAdminEventScanClaimBoundedExecutorAndP5ClientReachActualApplicationRoute() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.event(f.endpoint("SIMULATOR"));
            long task = f.task(id);
            long audits = f.count("audit_log");
            assertEquals(0, f.reception.count.get());
            f.context.getBean(DueDeliveryScanner.class).scanDueTasks();
            Receipt receipt = f.reception.receipts.poll(5, TimeUnit.SECONDS);
            assertNotNull(receipt, "正式内部 HTTP 未到达验签后的接收路径");
            assertEquals("POST", receipt.method());
            assertEquals(SimulatorInternalTargetRoute.PATH, receipt.path());
            assertEquals(id, receipt.eventId());
            assertEquals(Integer.parseInt(f.context.getEnvironment().getRequiredProperty("local.server.port")), receipt.port());
            assertEquals(receipt.requestId(), UUID.fromString(receipt.requestId()).toString());
            assertFalse(receipt.inTransaction());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCESS".equals(f.jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?",
                    String.class, task)) && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("SUCCESS", f.jdbc.queryForObject("SELECT status FROM delivery_task WHERE id = ?", String.class, task));
            assertEquals(1, f.jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, task));
            assertEquals(1L, f.jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ? "
                    + "AND attempt_no = 1 AND result = 'SUCCESS' AND http_status = 200 AND ended_at IS NOT NULL",
                    Long.class, task));
            assertEquals("{\"signatureVerified\":true,\"businessProcessed\":true}",
                    f.jdbc.queryForObject("SELECT response_excerpt FROM delivery_attempt WHERE task_id = ?", String.class, task));
            assertEquals(1L, f.jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE event_id = ? "
                    + "AND latest_round_no = 1 AND current_status = 'SUCCESS' AND terminal_at IS NOT NULL", Long.class, id));
            f.context.getBean(DueDeliveryScanner.class).scanDueTasks();
            assertEquals(1, f.reception.count.get());
            assertEquals(audits, f.count("audit_log"));
            assertEquals(1L, f.count("simulator_event_state"));
            assertEquals(1L, f.count("simulator_request_log"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ConfigurableApplicationContext context;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        final JsonMapper mapper;
        final JdbcTemplate jdbc;
        final ObservedReception reception;
        final String base;
        final String token;
        final String apiKey;

        Fixture() throws Exception {
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            context = new SpringApplicationBuilder(HookflowBackendApplication.class, TestConfiguration.class).run(
                    "--spring.profiles.active=p2-login-test", "--server.port=0", "--HOOKFLOW_REDIS_ENABLED=false",
                    "--DB_URL=" + MYSQL.getJdbcUrl(), "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                    "--HOOKFLOW_ADMIN_USERNAME=p801-admin", "--HOOKFLOW_ADMIN_PASSWORD=p801-admin-password",
                    "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
            mapper = context.getBean(JsonMapper.class); jdbc = context.getBean(JdbcTemplate.class);
            reception = context.getBean(ObservedReception.class);
            base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            token = mapper.readTree(api("/api/v1/auth/login", "{\"username\":\"p801-admin\",\"password\":\"p801-admin-password\"}", null).body())
                    .path("token").asString();
            apiKey = mapper.readTree(api("/api/v1/admin/api-key/rotate", "", token).body()).path("apiKey").asString();
        }

        long endpoint(String type) throws Exception {
            String url = type.equals("EXTERNAL") ? ",\"url\":\"https://hooks.example.com/receive\"" : "";
            var response = api("/api/v1/admin/endpoints", "{\"name\":\"p801-" + UUID.randomUUID()
                    + "\",\"targetType\":\"" + type + "\"" + url + "}", token);
            assertEquals(201, response.statusCode(), response.body());
            return mapper.readTree(response.body()).path("id").asLong();
        }

        String event(long endpoint) throws Exception {
            var response = api("/api/v1/admin/events/test", "{\"eventType\":\"order.paid\",\"endpointId\":"
                    + endpoint + ",\"payload\":{\"amount\":1}}", token);
            assertEquals(202, response.statusCode(), response.body());
            return mapper.readTree(response.body()).path("eventId").asString();
        }

        long task(String event) {
            return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id = t.event_pk "
                    + "WHERE e.event_id = ? AND t.round_no = e.latest_round_no", Long.class, event);
        }

        String secret(String id) {
            return context.getBean(EndpointSigningSecretCipher.class).decryptForDelivery(jdbc.queryForObject(
                    "SELECT secret_ciphertext_snapshot FROM delivery_task WHERE id = ?", (rs, row) -> rs.getBytes(1), task(id)));
        }

        Map<String, String> headers(String id, String timestamp, byte[] body, String secret) {
            var headers = new LinkedHashMap<String, String>();
            headers.put(HEADER_NAMES[0], id); headers.put(HEADER_NAMES[1], timestamp);
            headers.put(HEADER_NAMES[2], context.getBean(DeliveryRequestSigner.class).sign(id, timestamp, body, secret));
            return headers;
        }

        HttpResponse<String> signed(String id, String timestamp, byte[] body, String secret) throws Exception {
            return receive(headers(id, timestamp, body, secret), body);
        }

        HttpRequest.Builder request(Map<String, String> headers, byte[] body) {
            var request = HttpRequest.newBuilder(URI.create(base + SimulatorInternalTargetRoute.PATH))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-Request-Id", "forged-client-request-id").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header); return request;
        }

        HttpResponse<String> receive(Map<String, String> headers, byte[] body) throws Exception { return send(request(headers, body).build()); }
        HttpResponse<String> send(HttpRequest request) throws Exception {
            var state = jdbc.queryForList("SELECT * FROM simulator_event_state ORDER BY event_id");
            long logs = count("simulator_request_log");
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 400 || response.statusCode() == 401) {
                assertEquals(state, jdbc.queryForList("SELECT * FROM simulator_event_state ORDER BY event_id"));
                if (request.method().equals("POST") && request.uri().getPath().equals(SimulatorInternalTargetRoute.PATH)) {
                    assertEquals(logs + 1, count("simulator_request_log"));
                    var row = jdbc.queryForMap("SELECT * FROM simulator_request_log ORDER BY id DESC LIMIT 1");
                    assertNull(row.get("event_id")); assertNull(row.get("config_mode"));
                    assertEquals(false, row.get("signature_valid"));
                    assertEquals(false, row.get("duplicate_processed"));
                    assertEquals(response.statusCode(), ((Number) row.get("planned_status")).intValue());
                    assertEquals(java.time.LocalDateTime.ofInstant(context.getBean(MutableClock.class).instant(),
                            java.time.ZoneOffset.UTC), row.get("received_at"));
                } else assertEquals(logs, count("simulator_request_log"));
            }
            return response;
        }
        HttpResponse<String> api(String path, String body, String admin) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (admin != null) request.header("Authorization", "Bearer " + admin);
            return send(request.build());
        }

        void error(HttpResponse<String> response, int status, String code) throws Exception {
            assertEquals(status, response.statusCode(), response.body());
            JsonNode body = mapper.readTree(response.body());
            assertEquals(3, body.size()); assertEquals(code, body.path("code").asString());
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertEquals(requestId, UUID.fromString(requestId).toString());
            assertEquals(requestId, body.path("requestId").asString());
            assertNotEquals("forged-client-request-id", requestId);
            assertFalse(response.body().contains(token)); assertFalse(response.body().contains(apiKey));
            assertFalse(response.body().contains("ciphertext")); assertFalse(response.body().contains("Exception"));
            response.request().headers().firstValue(HEADER_NAMES[2]).filter(value -> value.length() == 64)
                    .ifPresent(value -> assertFalse(response.body().contains(value)));
            assertFalse(response.body().contains(new String(BODY, StandardCharsets.UTF_8)));
            if (status == 401 && response.request().uri().getPath().equals(SimulatorInternalTargetRoute.PATH)
                    && response.request().method().equals("POST")) {
                assertEquals("模拟器签名认证失败", body.path("message").asString());
            }
        }

        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
        String databaseFingerprint() throws Exception {
            var rows = new LinkedHashMap<String, Object>();
            for (String table : List.of("endpoint", "event", "delivery_task", "delivery_attempt", "audit_log")) {
                rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(rows)));
        }
        @Override public void close() {
            client.close();
            context.close();
        }
    }
}
