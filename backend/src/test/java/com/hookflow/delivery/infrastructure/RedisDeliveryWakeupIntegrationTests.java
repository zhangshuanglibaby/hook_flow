package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.delivery.application.BoundedDeliveryExecutor;
import com.hookflow.delivery.application.DeliveryWakeup;
import com.hookflow.delivery.application.DueDeliveryScanner;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.event.api.EventRequestJsonParser;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.application.EventSubmissionService;
import com.hookflow.event.domain.CanonicalEventPayload;
import com.hookflow.support.TestHttpReceiver;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ClientKillParams;
import redis.clients.jedis.args.ClientType;

@Testcontainers
class RedisDeliveryWakeupIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis@sha256:aa049e689e141a4358ad1d4562dc49c88a89fbab711fd8fcc33f684c80b26301")
            .withExposedPorts(6379);

    @AfterEach
    void clearFixtures() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM endpoint");
    }

    @Test
    void publishesOnlyAfterRealOuterCommitAndSubscriberUsesFormalScannerOnce() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, false, REDIS.getMappedPort(6379));
                Probe probe = new Probe(context)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(2);
            String id = id();
            TransactionTemplate transaction = transaction(context);
            transaction.executeWithoutResult(status -> {
                context.getBean(EventCreationService.class).create(id, "order.paid", endpoint, payload());
                assertEquals(0, probe.messages.get());
                assertEquals(0L, externalJdbc().queryForObject(
                        "SELECT COUNT(*) FROM event WHERE event_id = ?", Long.class, id));
                try {
                    assertNull(receiver.awaitRequest(Duration.ofMillis(250)));
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                assertEquals(0, probe.messages.get());
            });
            await(() -> probe.messages.get() == 1);
            assertEquals(1, probe.committedPairs.get());
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            assertOneSuccess(jdbc, id);
            try (Jedis redis = redis()) {
                for (int i = 0; i < 30; i++) redis.publish(RedisDeliveryWakeup.CHANNEL, RedisDeliveryWakeup.SIGNAL);
            }
            Thread.sleep(200);
            assertOneSuccess(jdbc, id);
            assertNull(receiver.awaitRequest(Duration.ofMillis(200)));
        }
    }

    @Test
    void rollbackDuplicateConflictAndTaskInsertFailureDoNotPublish() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, false, REDIS.getMappedPort(6379));
                Probe probe = new Probe(context)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(2);
            String rolledBack = id();
            transaction(context).executeWithoutResult(status -> {
                context.getBean(EventCreationService.class).create(rolledBack, "order.paid", endpoint, payload());
                status.setRollbackOnly();
            });
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE event_id = ?",
                    Long.class, rolledBack));
            String failed = id();
            jdbc.execute("ALTER TABLE delivery_task ADD CONSTRAINT ck_p604_reject CHECK (round_no <> 1)");
            try {
                assertThrows(DataAccessException.class, () -> context.getBean(EventCreationService.class)
                        .create(failed, "order.paid", endpoint, payload()));
            } finally {
                jdbc.execute("ALTER TABLE delivery_task DROP CHECK ck_p604_reject");
            }
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE event_id = ?",
                    Long.class, failed));
            assertNull(receiver.awaitRequest(Duration.ofMillis(300)));
            assertEquals(0, probe.messages.get());
            String accepted = id();
            var submission = context.getBean(EventSubmissionService.class);
            assertFalse(submission.submit(accepted, "order.paid", endpoint, payload()).duplicate());
            await(() -> probe.messages.get() == 1);
            assertTrue(submission.submit(accepted, "order.paid", endpoint, payload()).duplicate());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> submission.submit(accepted, "different.type", endpoint, payload()));
            Thread.sleep(300);
            assertEquals(1, probe.messages.get());
            await(() -> "SUCCESS".equals(eventStatus(jdbc, accepted)));
            assertOneSuccess(jdbc, accepted);
        }
    }

    @Test
    void concurrentNotificationsRespectExistingCapacityAndDuePausedGuards() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, false, REDIS.getMappedPort(6379))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(1);
            List<BoundedDeliveryExecutor.Reservation> reservations = new ArrayList<>();
            var executor = context.getBean(BoundedDeliveryExecutor.class);
            for (int i = 0; i < 20; i++) reservations.add(executor.reserve().orElseThrow());
            List<String> ids = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    String id = id();
                    ids.add(id);
                    context.getBean(EventCreationService.class).create(id, "order.paid", endpoint, payload());
                }
                jdbc.update("UPDATE delivery_task t JOIN event e ON t.event_pk=e.id "
                        + "SET t.status='WAIT_RETRY', t.next_run_at='2099-01-01', e.current_status='WAIT_RETRY' "
                        + "WHERE e.event_id=?", ids.get(1));
                jdbc.update("UPDATE delivery_task t JOIN event e ON t.event_pk=e.id "
                        + "SET t.status='PAUSED', t.next_run_at=NULL, t.resume_status='PENDING', "
                        + "t.resume_due_at=t.created_at, e.current_status='PAUSED' WHERE e.event_id=?", ids.get(2));
                notifyConcurrently();
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class));
                assertEquals("PENDING", eventStatus(jdbc, ids.getFirst()));
                assertNull(receiver.awaitRequest(Duration.ofMillis(250)));
            } finally {
                reservations.forEach(BoundedDeliveryExecutor.Reservation::close);
            }
            notifyConcurrently();
            await(() -> "SUCCESS".equals(eventStatus(jdbc, ids.getFirst())));
            assertOneSuccess(jdbc, ids.getFirst());
            assertEquals("WAIT_RETRY", eventStatus(jdbc, ids.get(1)));
            assertEquals("PAUSED", eventStatus(jdbc, ids.get(2)));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class));
        }
    }

    @Test
    void missingRedisAtStartupDoesNotPreventPeriodicMysqlDelivery() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0)) { unused = socket.getLocalPort(); }
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, true, unused)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String id = id();
            long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username='p604-admin'", Long.class);
            String key = context.getBean(com.hookflow.auth.application.BusinessApiKeyService.class)
                    .rotate(actor, UUID.randomUUID().toString());
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"
                            + context.getEnvironment().getProperty("local.server.port") + "/api/v1/events"))
                            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                            .header("X-HookFlow-Api-Key", key)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"eventId\":\"" + id
                                    + "\",\"eventType\":\"order.paid\",\"endpointId\":" + endpoint(context)
                                    + ",\"payload\":null}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode());
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            assertOneSuccess(jdbc, id);
        }
    }

    @Test
    void disconnectedSubscriptionLosesSignalButPeriodicScanWorksAndResubscribes() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, true, REDIS.getMappedPort(6379))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(1);
            try (Jedis redis = redis()) {
                redis.clientKill(ClientKillParams.clientKillParams().type(ClientType.PUBSUB));
                assertEquals(0L, redis.publish(RedisDeliveryWakeup.CHANNEL, RedisDeliveryWakeup.SIGNAL));
            }
            String id = id();
            insertWithoutSignal(jdbc, id, endpoint);
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            assertOneSuccess(jdbc, id);
            awaitSubscribers(1);
        }
    }

    @Test
    void resubscribedConnectionWakesScannerWithoutPeriodicScheduling() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, false, REDIS.getMappedPort(6379))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(1);
            try (Jedis redis = redis()) {
                assertTrue(redis.clientKill(ClientKillParams.clientKillParams().type(ClientType.PUBSUB)) > 0);
            }
            awaitSubscribers(1);
            Thread.sleep(5500);
            try (Jedis redis = redis()) {
                assertEquals(1L, redis.pubsubNumSub(RedisDeliveryWakeup.CHANNEL).get(RedisDeliveryWakeup.CHANNEL));
            }
            String id = id();
            insertWithoutSignal(jdbc, id, endpoint);
            try (Jedis redis = redis()) {
                redis.publish(RedisDeliveryWakeup.CHANNEL, RedisDeliveryWakeup.SIGNAL);
            }
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            assertOneSuccess(jdbc, id);
        }
    }

    @Test
    void concurrentSameEventPublishesOnlyWinningCommit() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, false, REDIS.getMappedPort(6379));
                Probe probe = new Probe(context)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(2);
            String id = id();
            var service = context.getBean(EventSubmissionService.class);
            CountDownLatch go = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> { go.await(); return service.submit(id, "order.paid", endpoint, payload()); });
                var second = workers.submit(() -> { go.await(); return service.submit(id, "order.paid", endpoint, payload()); });
                go.countDown();
                assertNotEquals(first.get(5, TimeUnit.SECONDS).duplicate(), second.get(5, TimeUnit.SECONDS).duplicate());
            }
            await(() -> probe.messages.get() == 1);
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            Thread.sleep(250);
            assertEquals(1, probe.messages.get());
            assertOneSuccess(jdbc, id);
        }
    }

    @Test
    void runtimeRedisOutageKeepsMysqlDeliveryAndReconnectsAfterRestart() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, true, REDIS.getMappedPort(6379))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long endpoint = endpoint(context);
            awaitSubscribers(1);
            var docker = REDIS.getDockerClient();
            docker.pauseContainerCmd(REDIS.getContainerId()).exec();
            String id = id();
            try {
                Thread.sleep(5500);
                assertFalse(context.getBean(EventSubmissionService.class)
                        .submit(id, "order.paid", endpoint, payload()).duplicate());
                assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
                await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
                assertOneSuccess(jdbc, id);
            } finally {
                docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
            awaitSubscribers(1);
        }
    }

    @Test
    void redisAclPublishErrorDoesNotRollbackAcceptedEventAndLogsNoCredentials() throws Exception {
        String username = "p604-user";
        String password = "p604-secret-" + UUID.randomUUID();
        try (Jedis redis = redis()) {
            redis.aclSetUser(username, "on", ">" + password, "~*", "&*", "+subscribe", "+ping");
        }
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RedisDeliveryWakeup.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                ConfigurableApplicationContext context = start(receiver, true, REDIS.getMappedPort(6379),
                        "--hookflow.redis.username=" + username, "--hookflow.redis.password=" + password)) {
            logs.start();
            logger.addAppender(logs);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String id = id();
            assertFalse(context.getBean(EventSubmissionService.class)
                    .submit(id, "order.paid", endpoint(context), payload()).duplicate());
            await(() -> logs.list.stream().anyMatch(log -> log.getFormattedMessage()
                    .contains("redisWakeupPublishFailed exceptionType=JedisAccessControlException")));
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(5)));
            await(() -> "SUCCESS".equals(eventStatus(jdbc, id)));
            assertOneSuccess(jdbc, id);
            assertTrue(logs.list.stream().noneMatch(log -> log.getFormattedMessage().contains(password)
                    || log.getFormattedMessage().contains(username) || log.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            try (Jedis redis = redis()) { redis.aclDelUser(username); }
        }
    }

    private static void insertWithoutSignal(JdbcTemplate jdbc, String id, long endpoint) {
        jdbc.update("INSERT INTO event(event_id,event_type,payload_json,payload_fingerprint,endpoint_id,"
                + "latest_round_no,current_status,created_at) VALUES(?,'order.paid','null',"
                + "UNHEX(SHA2('null',256)),?,1,'PENDING','2020-01-01')", id, endpoint);
        jdbc.update("INSERT INTO delivery_task(event_pk,round_no,status,attempt_count,max_attempts,"
                + "retry_delay_1_s,retry_delay_2_s,next_run_at,target_type_snapshot,timeout_ms_snapshot,"
                + "secret_ciphertext_snapshot,created_at,updated_at) SELECT e.id,1,'PENDING',0,3,10,30,"
                + "'2020-01-01','SIMULATOR',5000,p.signing_secret_ciphertext,'2020-01-01','2020-01-01' "
                + "FROM event e JOIN endpoint p ON e.endpoint_id=p.id WHERE e.event_id=?", id);
    }

    private static void notifyConcurrently() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> {
                try (Jedis redis = redis()) {
                    for (int n = 0; n < 20; n++) redis.publish(RedisDeliveryWakeup.CHANNEL, RedisDeliveryWakeup.SIGNAL);
                }
            }));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        Thread.sleep(250);
    }

    private static ConfigurableApplicationContext start(TestHttpReceiver receiver, boolean scheduled, int port,
            String... extra) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var builder = new SpringApplicationBuilder(HookflowBackendApplication.class, ReceiverRoute.class);
        if (!scheduled) builder.profiles("p2-login-test");
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--p604.receiver.port=" + receiver.simulatorUri().getPort(),
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=p604-admin", "--HOOKFLOW_ADMIN_PASSWORD=p604-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key),
                "--hookflow.redis.enabled=true", "--hookflow.redis.host=" + REDIS.getHost(),
                "--hookflow.redis.port=" + port));
        args.addAll(List.of(extra));
        return builder.run(args.toArray(String[]::new));
    }

    private static long endpoint(ConfigurableApplicationContext context) {
        long actor = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT id FROM app_user WHERE username='p604-admin'", Long.class);
        return context.getBean(EndpointSecretProvisioningService.class).createSimulator(
                "p604-" + UUID.randomUUID(), 5000, true, actor, UUID.randomUUID().toString()).id();
    }

    private static JdbcTemplate externalJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        TransactionTemplate result = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return result;
    }

    private static String eventStatus(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?", String.class, id);
    }

    private static void assertOneSuccess(JdbcTemplate jdbc, String id) {
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task t JOIN event e ON e.id=t.event_pk "
                + "WHERE e.event_id=? AND t.status='SUCCESS' AND t.attempt_count=1 AND e.current_status='SUCCESS'",
                Long.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt a JOIN delivery_task t ON t.id=a.task_id "
                + "JOIN event e ON e.id=t.event_pk WHERE e.event_id=? AND a.result='SUCCESS' AND a.http_status=200",
                Long.class, id));
    }

    private static String id() { return "p604-" + UUID.randomUUID(); }
    private static CanonicalEventPayload payload() {
        return new EventRequestJsonParser().parse(new ByteArrayInputStream(
                "{\"payload\":null}".getBytes(StandardCharsets.UTF_8))).payload();
    }
    private static Jedis redis() { return new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379)); }
    private static void awaitSubscribers(int count) throws Exception {
        await(() -> {
            try (Jedis redis = redis()) {
                return redis.pubsubNumSub(RedisDeliveryWakeup.CHANNEL).get(RedisDeliveryWakeup.CHANNEL) >= count;
            } catch (redis.clients.jedis.exceptions.JedisConnectionException exception) {
                return false;
            }
        });
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "condition did not complete within bounded wait");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ReceiverRoute {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        SimulatorInternalTargetRoute testReceiverRoute(org.springframework.core.env.Environment environment) {
            var fixed = new org.springframework.mock.env.MockEnvironment();
            fixed.setProperty("server.port", environment.getRequiredProperty("p604.receiver.port"));
            return new SimulatorInternalTargetRoute(fixed);
        }
    }

    private static final class Probe implements AutoCloseable {
        final AtomicInteger messages = new AtomicInteger();
        final AtomicInteger committedPairs = new AtomicInteger();
        final Jedis connection = redis();
        final JedisPubSub listener;
        final Thread thread;
        Probe(ConfigurableApplicationContext context) throws Exception {
            CountDownLatch ready = new CountDownLatch(1);
            listener = new JedisPubSub() {
                @Override public void onSubscribe(String channel, int count) { ready.countDown(); }
                @Override public void onMessage(String channel, String message) {
                    if (RedisDeliveryWakeup.SIGNAL.equals(message)) {
                        Long pairs = externalJdbc().queryForObject("SELECT COUNT(*) FROM event e JOIN delivery_task t "
                                + "ON t.event_pk=e.id WHERE e.event_id LIKE 'p604-%'", Long.class);
                        if (pairs > 0) committedPairs.incrementAndGet();
                        messages.incrementAndGet();
                    }
                }
            };
            thread = new Thread(() -> connection.subscribe(listener, RedisDeliveryWakeup.CHANNEL));
            thread.setDaemon(true);
            thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception {
            listener.unsubscribe();
            thread.join(2000);
            connection.close();
        }
    }
}
