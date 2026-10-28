package com.hookflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.application.EventSubmissionService;
import com.hookflow.event.application.ExistingEventLookupService;
import com.hookflow.event.domain.CanonicalEventPayload;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.hookflow.event.infrastructure.EventRepository.ExistingEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EventDeduplicationHttpIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @Test
    void concurrentIdenticalAndConflictingSubmissionsKeepOneEventAndFirstTask() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            Fixture fixture = fixture(context);
            long endpointId = endpoint(context, fixture, true).id();
            String sameId = "dedupe-same-" + UUID.randomUUID();
            String sameBody = request(sameId, endpointId, "{\"b\":2,\"a\":1}");

            List<HttpResponse<String>> same = concurrently(fixture, sameBody, sameBody);
            assertEquals(1, same.stream().filter(response -> response.statusCode() == 202).count());
            assertEquals(1, same.stream().filter(response -> response.statusCode() == 200).count());
            JsonNode first = fixture.mapper().readTree(same.stream()
                    .filter(response -> response.statusCode() == 202).findFirst().orElseThrow().body());
            JsonNode duplicate = fixture.mapper().readTree(same.stream()
                    .filter(response -> response.statusCode() == 200).findFirst().orElseThrow().body());
            assertFalse(first.path("duplicate").asBoolean());
            assertTrue(duplicate.path("duplicate").asBoolean());
            assertEquals(first.path("createdAt").asString(), duplicate.path("createdAt").asString());
            assertEquals("PENDING", duplicate.path("status").asString());
            assertEquals(1, duplicate.path("latestRoundNo").asInt());
            assertOnlyFirstTask(fixture.jdbc(), sameId);

            String differentId = "dedupe-different-" + UUID.randomUUID();
            List<HttpResponse<String>> different = concurrently(fixture,
                    request(differentId, endpointId, "{\"a\":1}"),
                    request(differentId, endpointId, "{\"a\":2}"));
            assertEquals(1, different.stream().filter(response -> response.statusCode() == 202).count());
            assertEquals(1, different.stream().filter(response -> response.statusCode() == 409).count());
            assertError(fixture, different.stream()
                    .filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                    409, "CONFLICT");
            assertOnlyFirstTask(fixture.jdbc(), differentId);
        }
    }

    @Test
    void canonicalEquivalenceAndLaterEndpointChangesPreserveOriginalEvent() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            Fixture fixture = fixture(context);
            long endpointId = endpoint(context, fixture, true).id();
            long otherEndpointId = endpoint(context, fixture, true).id();
            String eventId = "dedupe-canonical-" + UUID.randomUUID();
            HttpResponse<String> accepted = submit(fixture,
                    request(eventId, endpointId, "{ \"z\" : [1.0, 2e0], \"a\": true }"));
            assertEquals(202, accepted.statusCode(), accepted.body());
            String createdAt = fixture.mapper().readTree(accepted.body()).path("createdAt").asString();

            for (String equivalent : List.of(
                    "{\"a\":true,\"z\":[1,2]}",
                    "{\n\"z\" : [1e0,2.00],\n\"a\":true}")) {
                JsonNode response = assertDuplicate(fixture,
                        submit(fixture, request(eventId, endpointId, equivalent)), createdAt);
                assertEquals("PENDING", response.path("status").asString());
            }
            assertError(fixture, submit(fixture,
                    request(eventId, endpointId, "{\"a\":true,\"z\":[2,1]}")),
                    409, "CONFLICT");
            assertError(fixture, submit(fixture, request(eventId, otherEndpointId,
                    "{\"a\":true,\"z\":[1,2]}")), 409, "CONFLICT");
            assertError(fixture, submit(fixture, request(eventId, endpointId,
                    "{\"a\":true,\"z\":[1,2]}").replace("order.paid", "order.cancelled")),
                    409, "CONFLICT");

            fixture.jdbc().update("UPDATE endpoint SET enabled = 0, timeout_ms = 7000 WHERE id = ?",
                    endpointId);
            Long eventPk = fixture.jdbc().queryForObject(
                    "SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
            fixture.jdbc().update("UPDATE delivery_task SET status = 'PAUSED', "
                    + "resume_status = 'PENDING', resume_due_at = next_run_at, updated_at = "
                    + "DATE_ADD(updated_at, INTERVAL 1 SECOND) WHERE event_pk = ?", eventPk);
            fixture.jdbc().update("UPDATE event SET current_status = 'PAUSED' WHERE id = ?", eventPk);
            JsonNode duplicate = assertDuplicate(fixture, submit(fixture,
                    request(eventId, endpointId, "{\"a\":true,\"z\":[1,2]}")), createdAt);
            assertEquals("PAUSED", duplicate.path("status").asString());
            assertEquals(1, duplicate.path("latestRoundNo").asInt());
            assertError(fixture, submit(fixture,
                    request(eventId, endpointId, "{\"a\":false,\"z\":[1,2]}")),
                    409, "CONFLICT");
            assertError(fixture, submit(fixture, request("new-" + UUID.randomUUID(),
                    endpointId, "null")), 422, "ENDPOINT_DISABLED");
            assertOnlyFirstTask(fixture.jdbc(), eventId);

            // A synthetic second round is only a database fixture; this test does not
            // implement retries or redelivery.
            fixture.jdbc().update("INSERT INTO delivery_task (event_pk, round_no, status, "
                    + "attempt_count, max_attempts, retry_delay_1_s, retry_delay_2_s, "
                    + "next_run_at, target_type_snapshot, url_snapshot, "
                    + "timeout_ms_snapshot, secret_ciphertext_snapshot, created_at, updated_at) "
                    + "SELECT event_pk, 2, 'PENDING', 0, max_attempts, retry_delay_1_s, "
                    + "retry_delay_2_s, DATE_ADD(created_at, INTERVAL 2 SECOND), "
                    + "target_type_snapshot, url_snapshot, timeout_ms_snapshot, "
                    + "secret_ciphertext_snapshot, DATE_ADD(created_at, INTERVAL 2 SECOND), "
                    + "DATE_ADD(created_at, INTERVAL 2 SECOND) "
                    + "FROM delivery_task WHERE event_pk = ? AND round_no = 1", eventPk);
            fixture.jdbc().update("UPDATE event SET latest_round_no = 2, "
                    + "current_status = 'PENDING' WHERE id = ?", eventPk);
            JsonNode latest = assertDuplicate(fixture, submit(fixture,
                    request(eventId, endpointId, "{\"a\":true,\"z\":[1,2]}")), createdAt);
            assertEquals(2, latest.path("latestRoundNo").asInt());
            assertEquals("PENDING", latest.path("status").asString());
            assertEquals(2L, fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ?", Long.class, eventPk));
            assertEquals(0L, fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM delivery_attempt WHERE task_id IN "
                            + "(SELECT id FROM delivery_task WHERE event_pk = ?)", Long.class, eventPk));
        }
    }

    @Test
    void staleInitialLookupExercisesDuplicateKeyRollbackAndFreshRead() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            Fixture fixture = fixture(context);
            long endpointId = endpoint(context, fixture, true).id();
            String eventId = "dedupe-rollback-" + UUID.randomUUID();
            HttpResponse<String> accepted = submit(fixture,
                    request(eventId, endpointId, "{\"value\":1}"));
            assertEquals(202, accepted.statusCode(), accepted.body());
            String createdAt = fixture.mapper().readTree(accepted.body()).path("createdAt").asString();
            CanonicalEventPayload same = new EventPayloadCanonicalizer()
                    .canonicalize(fixture.mapper().readTree("{\"value\":1.0}"));
            EventSubmissionService service = new EventSubmissionService(
                    staleFirstLookup(context), context.getBean(EventCreationService.class));

            EventSubmissionService.SubmissionResult duplicate =
                    service.submit(eventId, "order.paid", endpointId, same);
            assertTrue(duplicate.duplicate());
            assertEquals(createdAt, duplicate.createdAt().toString());
            assertEquals("PENDING", duplicate.status());
            assertEquals(1, duplicate.latestRoundNo());

            CanonicalEventPayload different = new EventPayloadCanonicalizer()
                    .canonicalize(fixture.mapper().readTree("{\"value\":2}"));
            EventSubmissionService conflicting = new EventSubmissionService(
                    staleFirstLookup(context), context.getBean(EventCreationService.class));
            ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                    () -> conflicting.submit(eventId, "order.paid", endpointId, different));
            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
            assertOnlyFirstTask(fixture.jdbc(), eventId);
        }
    }

    @Test
    void matchingFingerprintStillRequiresMatchingCanonicalContent() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            Fixture fixture = fixture(context);
            long endpointId = endpoint(context, fixture, true).id();
            String eventId = "dedupe-fingerprint-" + UUID.randomUUID();
            String original = request(eventId, endpointId, "{\"value\":1}");
            assertEquals(202, submit(fixture, original).statusCode());

            // Simulate an inconsistent stored fingerprint: the digest still matches
            // the request, but the stored canonical JSON does not.
            fixture.jdbc().update("UPDATE event SET payload_json = ? WHERE event_id = ?",
                    "{\"value\":2}", eventId);
            assertError(fixture, submit(fixture, original), 409, "CONFLICT");
            assertOnlyFirstTask(fixture.jdbc(), eventId);
        }
    }

    private static List<HttpResponse<String>> concurrently(Fixture fixture,
            String left, String right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for the concurrent start");
                }
                return submit(fixture, left);
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for the concurrent start");
                }
                return submit(fixture, right);
            });
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(40, TimeUnit.SECONDS), second.get(40, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    private static ExistingEventLookupService staleFirstLookup(
            ConfigurableApplicationContext context) {
        ExistingEventLookupService realLookup =
                context.getBean(ExistingEventLookupService.class);
        AtomicInteger calls = new AtomicInteger();
        return new ExistingEventLookupService(null) {
            @Override
            public Optional<ExistingEvent> findMatching(String eventId, String eventType,
                    long endpointId, CanonicalEventPayload payload) {
                if (calls.getAndIncrement() == 0) {
                    return Optional.empty();
                }
                return realLookup.findMatching(eventId, eventType, endpointId, payload);
            }
        };
    }

    private static void assertOnlyFirstTask(JdbcTemplate jdbc, String eventId) {
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM event WHERE event_id = ?", Long.class, eventId));
        Long eventPk = jdbc.queryForObject(
                "SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ? AND round_no = 1",
                Long.class, eventPk));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE event_pk = ?", Long.class, eventPk));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE task_id IN "
                        + "(SELECT id FROM delivery_task WHERE event_pk = ?)", Long.class, eventPk));
    }

    private static JsonNode assertDuplicate(Fixture fixture,
            HttpResponse<String> response, String createdAt) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = fixture.mapper().readTree(response.body());
        assertEquals(7, body.size());
        assertTrue(body.path("duplicate").asBoolean());
        assertEquals(createdAt, body.path("createdAt").asString());
        return body;
    }

    private static void assertError(Fixture fixture, HttpResponse<String> response,
            int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = fixture.mapper().readTree(response.body());
        assertEquals(3, body.size());
        assertEquals(code, body.path("code").asString());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),
                body.path("requestId").asString());
    }

    private static String request(String eventId, long endpointId, String payload) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"order.paid\","
                + "\"endpointId\":" + endpointId + ",\"payload\":" + payload + "}";
    }

    private static HttpResponse<String> submit(Fixture fixture, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(fixture.base() + "/api/v1/events"))
                .header("Content-Type", "application/json")
                .header("X-HookFlow-Api-Key", fixture.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return fixture.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static EndpointConfiguration endpoint(ConfigurableApplicationContext context,
            Fixture fixture, boolean enabled) {
        Long actor = fixture.jdbc().queryForObject(
                "SELECT id FROM app_user WHERE username = 'dedupe-http-test-admin'", Long.class);
        return context.getBean(EndpointSecretProvisioningService.class)
                .createSimulator("dedupe-http-" + UUID.randomUUID(), 5000, enabled,
                        actor, UUID.randomUUID().toString());
    }

    private static Fixture fixture(ConfigurableApplicationContext context) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        JsonMapper mapper = context.getBean(JsonMapper.class);
        String base = "http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port");
        HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"dedupe-http-test-admin\","
                                + "\"password\":\"dedupe-http-test-password\"}")).build();
        HttpResponse<String> loginResponse = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode(), loginResponse.body());
        String token = mapper.readTree(loginResponse.body()).path("token").asString();
        HttpRequest rotate = HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/api-key/rotate"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rotateResponse = client.send(rotate, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rotateResponse.statusCode(), rotateResponse.body());
        return new Fixture(client, mapper, context.getBean(JdbcTemplate.class), base,
                mapper.readTree(rotateResponse.body()).path("apiKey").asString());
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
                "--HOOKFLOW_ADMIN_USERNAME=dedupe-http-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=dedupe-http-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(keyBytes));
    }

    private record Fixture(HttpClient client, JsonMapper mapper,
            JdbcTemplate jdbc, String base, String apiKey) {
    }
}
