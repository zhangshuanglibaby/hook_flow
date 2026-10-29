package com.hookflow.delivery.infrastructure;

import com.hookflow.HookflowBackendApplication;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DueDeliveryTaskRepositoryIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 8, 30, 0);

    @AfterEach
    void clearFixture() {
        JdbcTemplate jdbc = jdbc();
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM delivery_task");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM endpoint");
    }

    @Test
    void scansDueTasksByTimeThenIdWithSmallBatchAndNoMutation() {
        JdbcTemplate jdbc = jdbc();
        long endpointId = insertEndpoint(jdbc);
        List<Long> sameTimeIds = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            sameTimeIds.add(insertTask(jdbc, endpointId, NOW.minusSeconds(1)));
        }
        long earlierRetry = insertTask(jdbc, endpointId, NOW.minusMinutes(2));
        jdbc.update("UPDATE delivery_task SET status = 'WAIT_RETRY', attempt_count = 1 WHERE id = ?",
                earlierRetry);
        long future = insertTask(jdbc, endpointId, NOW.plusNanos(1_000_000));
        long exhausted = insertTask(jdbc, endpointId, NOW.minusMinutes(3));
        jdbc.update("UPDATE delivery_task SET attempt_count = max_attempts WHERE id = ?", exhausted);
        long paused = insertTask(jdbc, endpointId, NOW.minusMinutes(4));
        jdbc.update("UPDATE delivery_task SET status = 'PAUSED', next_run_at = NULL, "
                + "resume_status = 'PENDING', resume_due_at = ? WHERE id = ?", NOW.minusMinutes(4), paused);
        long delivering = insertTask(jdbc, endpointId, NOW.minusMinutes(5));
        jdbc.update("UPDATE delivery_task SET status = 'DELIVERING', attempt_count = 1, "
                + "next_run_at = NULL, claim_token = ?, lease_until = ? WHERE id = ?",
                UUID.randomUUID().toString(), NOW.plusMinutes(1), delivering);

        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);
        List<Long> expected = new ArrayList<>();
        expected.add(earlierRetry);
        expected.addAll(sameTimeIds.subList(0, 15));
        assertEquals(expected, repository.findDueCandidateIds(NOW));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE id IN (?, ?, ?) AND claim_token IS NOT NULL",
                Long.class, earlierRetry, sameTimeIds.get(0), sameTimeIds.get(1)));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE id = ? AND attempt_count <> 0",
                Long.class, future));
    }

    @Test
    void cursorQueryContinuesAfterFullBatchInStableDueOrder() {
        JdbcTemplate jdbc = jdbc();
        long endpointId = insertEndpoint(jdbc);
        long earlier = insertTask(jdbc, endpointId, NOW.minusMinutes(2));
        List<Long> tied = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tied.add(insertTask(jdbc, endpointId, NOW.minusMinutes(1)));
        }
        long later = insertTask(jdbc, endpointId, NOW);
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);

        var first = repository.findDueCandidatesAfter(NOW, null, 0);
        assertEquals(16, first.size());
        assertEquals(earlier, first.getFirst().id());
        assertEquals(tied.get(14), first.getLast().id());
        assertEquals(NOW.minusMinutes(1), first.getLast().dueAt());

        var second = repository.findDueCandidatesAfter(NOW,
                first.getLast().dueAt(), first.getLast().id());
        assertEquals(List.of(tied.get(15), later), second.stream().map(
                DueDeliveryTaskRepository.DueCandidate::id).toList());
        assertTrue(repository.findDueCandidatesAfter(NOW,
                second.getLast().dueAt(), second.getLast().id()).isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Long.class));
    }

    @Test
    void rejectsMissingScanTime() {
        assertThrows(NullPointerException.class,
                () -> new DueDeliveryTaskRepository(null).findDueCandidateIds(null));
    }

    @Test
    void locatesCandidateEventWithoutClaimingIt() {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.minusSeconds(1));
        long eventPk = jdbc.queryForObject(
                "SELECT event_pk FROM delivery_task WHERE id = ?", Long.class, taskId);
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);

        assertEquals(eventPk, repository.findEventPk(taskId).orElseThrow());
        assertTrue(repository.findEventPk(Long.MAX_VALUE).isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, taskId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?", Long.class, taskId));
    }

    @Test
    void lockedCandidateReadsCurrentFieldsAndDoesNotMutateTask() {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.plusDays(100));
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);
        long eventPk = repository.findEventPk(taskId).orElseThrow();
        jdbc.update("UPDATE delivery_task SET status = 'WAIT_RETRY', attempt_count = 1, "
                + "next_run_at = ? WHERE id = ?", NOW.plusDays(99), taskId);

        DueDeliveryTaskRepository.LockedDueTask task = transaction(jdbc).execute(status ->
                repository.lockCandidate(taskId).orElseThrow());

        assertEquals(taskId, task.id());
        assertEquals(eventPk, task.eventPk());
        assertEquals(1, task.roundNo());
        assertEquals("WAIT_RETRY", task.status());
        assertEquals(1, task.attemptCount());
        assertEquals(3, task.maxAttempts());
        assertEquals(NOW.plusDays(99), task.nextRunAt());
        assertEquals(5000, task.timeoutMsSnapshot());
        assertEquals(Boolean.TRUE, transaction(jdbc).execute(
                status -> repository.lockCandidate(Long.MAX_VALUE).isEmpty()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?", Long.class, taskId));
        assertEquals("WAIT_RETRY", jdbc.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", String.class, taskId));
    }

    @Test
    void skipsTaskLockedByAnotherTransaction() throws Exception {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.plusDays(100));
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = threads.submit(() -> transaction(jdbc).execute(status -> {
                repository.lockCandidate(taskId).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试任务行锁未及时释放");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试行锁等待被中断", exception);
                }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                Future<Boolean> contender = threads.submit(() -> transaction(jdbc).execute(status ->
                        repository.lockCandidate(taskId).isEmpty()));
                assertTrue(contender.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertEquals(Boolean.TRUE, transaction(jdbc).execute(
                status -> repository.lockCandidate(taskId).isPresent()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?", Long.class, taskId));
    }

    @Test
    void conditionalUpdateConsumesExactlyOneAttemptWithoutCreatingAnAttemptRow() {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.minusSeconds(1));
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = NOW.plusSeconds(36);

        assertEquals(Integer.valueOf(1), transaction(jdbc).execute(status -> repository.markDelivering(
                repository.lockCandidate(taskId).orElseThrow(), NOW, claimToken, leaseUntil)));

        assertEquals("DELIVERING", jdbc.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", String.class, taskId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, taskId));
        assertEquals(claimToken, jdbc.queryForObject(
                "SELECT claim_token FROM delivery_task WHERE id = ?", String.class, taskId));
        assertEquals(leaseUntil, jdbc.queryForObject(
                "SELECT lease_until FROM delivery_task WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), taskId));
        assertEquals(NOW, jdbc.queryForObject(
                "SELECT updated_at FROM delivery_task WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), taskId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?", Long.class, taskId));
    }

    @Test
    void conditionalUpdateRejectsPrematureStaleAndShortLeaseAndRollsBack() {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.plusMinutes(1));
        DueDeliveryTaskRepository repository = new DueDeliveryTaskRepository(jdbc);
        DueDeliveryTaskRepository.LockedDueTask snapshot = transaction(jdbc).execute(status ->
                repository.lockCandidate(taskId).orElseThrow());
        String token = UUID.randomUUID().toString();

        assertEquals(Integer.valueOf(0), transaction(jdbc).execute(status ->
                repository.markDelivering(snapshot, NOW, token, NOW.plusSeconds(36))));
        assertThrows(IllegalArgumentException.class, () -> transaction(jdbc).execute(status ->
                repository.markDelivering(snapshot, NOW.plusMinutes(1), token, NOW.plusMinutes(1).plusSeconds(35))));
        assertThrows(IllegalArgumentException.class, () -> transaction(jdbc).execute(status ->
                repository.markDelivering(snapshot, NOW.plusMinutes(1), " ", NOW.plusMinutes(2))));

        assertThrows(IllegalStateException.class, () -> transaction(jdbc).execute(status -> {
            assertEquals(1, repository.markDelivering(snapshot, NOW.plusMinutes(1), token,
                    NOW.plusMinutes(1).plusSeconds(36)));
            throw new IllegalStateException("测试回滚");
        }));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", String.class, taskId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, taskId));

        assertEquals(Integer.valueOf(1), transaction(jdbc).execute(status ->
                repository.markDelivering(snapshot, NOW.plusMinutes(1), token,
                        NOW.plusMinutes(1).plusSeconds(36))));
        assertEquals(Integer.valueOf(0), transaction(jdbc).execute(status ->
                repository.markDelivering(snapshot, NOW.plusMinutes(1), UUID.randomUUID().toString(),
                        NOW.plusMinutes(1).plusSeconds(36))));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, taskId));
    }

    @Test
    void springManagedUpdateRequiresCallerTransaction() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                HookflowBackendApplication.class).run(
                        "--spring.profiles.active=p2-login-test", "--server.port=0",
                        "--DB_URL=" + MYSQL.getJdbcUrl(),
                        "--DB_USERNAME=" + MYSQL.getUsername(),
                        "--DB_PASSWORD=" + MYSQL.getPassword(),
                        "--HOOKFLOW_ADMIN_USERNAME=due-task-test-admin",
                        "--HOOKFLOW_ADMIN_PASSWORD=due-task-test-password",
                        "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.minusSeconds(1));
            DueDeliveryTaskRepository repository = context.getBean(DueDeliveryTaskRepository.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            DueDeliveryTaskRepository.LockedDueTask snapshot = transaction.execute(status ->
                    repository.lockCandidate(taskId).orElseThrow());
            assertThrows(IllegalTransactionStateException.class, () ->
                    repository.markDelivering(snapshot, NOW, UUID.randomUUID().toString(), NOW.plusSeconds(36)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT attempt_count FROM delivery_task WHERE id = ?", Integer.class, taskId));
        }
    }

    @Test
    void startedAttemptRequiresCallerTransactionAndUniqueSequence() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                HookflowBackendApplication.class).run(
                        "--spring.profiles.active=p2-login-test", "--server.port=0",
                        "--DB_URL=" + MYSQL.getJdbcUrl(),
                        "--DB_USERNAME=" + MYSQL.getUsername(),
                        "--DB_PASSWORD=" + MYSQL.getPassword(),
                        "--HOOKFLOW_ADMIN_USERNAME=attempt-repository-test-admin",
                        "--HOOKFLOW_ADMIN_PASSWORD=attempt-repository-test-password",
                        "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW);
            DeliveryAttemptRepository attempts = context.getBean(DeliveryAttemptRepository.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            String requestId = UUID.randomUUID().toString();

            assertThrows(IllegalTransactionStateException.class,
                    () -> attempts.insertStarted(taskId, 1, NOW, requestId));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, taskId));

            assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
                attempts.insertStarted(taskId, 1, NOW, requestId);
                throw new IllegalStateException("测试领取事务回滚");
            }));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, taskId));

            transaction.executeWithoutResult(status -> attempts.insertStarted(taskId, 1, NOW, requestId));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, taskId));
            assertEquals("STARTED", jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ? AND attempt_no = 1",
                    String.class, taskId));
            assertEquals(NOW, jdbc.queryForObject(
                    "SELECT started_at FROM delivery_attempt WHERE task_id = ? AND attempt_no = 1",
                    (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), taskId));
            assertEquals(requestId, jdbc.queryForObject(
                    "SELECT request_id FROM delivery_attempt WHERE task_id = ? AND attempt_no = 1",
                    String.class, taskId));
            assertThrows(DuplicateKeyException.class, () -> transaction.executeWithoutResult(status ->
                    attempts.insertStarted(taskId, 1, NOW, UUID.randomUUID().toString())));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id = ?",
                    Long.class, taskId));
        }
    }

    @Test
    void finishingStartedAttemptPersistsSafeFieldsOnceAndRequiresTransaction() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                HookflowBackendApplication.class).run(
                        "--spring.profiles.active=p2-login-test", "--server.port=0",
                        "--DB_URL=" + MYSQL.getJdbcUrl(),
                        "--DB_USERNAME=" + MYSQL.getUsername(),
                        "--DB_PASSWORD=" + MYSQL.getPassword(),
                        "--HOOKFLOW_ADMIN_USERNAME=finish-attempt-test-admin",
                        "--HOOKFLOW_ADMIN_PASSWORD=finish-attempt-test-password",
                        "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DeliveryAttemptRepository attempts = context.getBean(DeliveryAttemptRepository.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW);
            String requestId = UUID.randomUUID().toString();
            transaction.executeWithoutResult(status -> attempts.insertStarted(taskId, 1, NOW, requestId));
            assertThrows(IllegalTransactionStateException.class, () -> attempts.lockStarted(taskId, 1));
            DeliveryAttemptRepository.LockedAttempt locked = transaction.execute(status ->
                    attempts.lockStarted(taskId, 1).orElseThrow());
            assertEquals(taskId, locked.taskId());
            assertEquals(1, locked.attemptNo());
            assertEquals(NOW, locked.startedAt());
            assertThrows(IllegalTransactionStateException.class, () -> attempts.finish(locked,
                    SingleDeliveryHttpResult.fromHttpResponse(503, "safe"), NOW.plusSeconds(2), 2000));

            assertEquals(Integer.valueOf(1), transaction.execute(status -> attempts.finish(
                    attempts.lockStarted(taskId, 1).orElseThrow(),
                    SingleDeliveryHttpResult.fromHttpResponse(503, "safe"), NOW.plusSeconds(2), 2000)));
            assertEquals("HTTP_FAILURE", jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertEquals(NOW.plusSeconds(2), jdbc.queryForObject(
                    "SELECT ended_at FROM delivery_attempt WHERE task_id = ?",
                    (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), taskId));
            assertEquals(2000L, jdbc.queryForObject(
                    "SELECT duration_ms FROM delivery_attempt WHERE task_id = ?", Long.class, taskId));
            assertEquals(503, jdbc.queryForObject(
                    "SELECT http_status FROM delivery_attempt WHERE task_id = ?", Integer.class, taskId));
            assertEquals("safe", jdbc.queryForObject(
                    "SELECT response_excerpt FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertEquals("HTTP_NON_SUCCESS", jdbc.queryForObject(
                    "SELECT error_code FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertEquals("接收端返回非成功状态", jdbc.queryForObject(
                    "SELECT error_message FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertTrue(Boolean.TRUE.equals(transaction.execute(status -> attempts.lockStarted(taskId, 1).isEmpty())));
            assertEquals(Integer.valueOf(0), transaction.execute(status -> attempts.finish(
                    locked, SingleDeliveryHttpResult.unknown(), NOW.plusSeconds(3), 3000)));
            assertEquals("HTTP_FAILURE", jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
        }
    }

    @Test
    void finishResultRollsBackAndUnknownDoesNotPretendRequestWasNotSent() {
        JdbcTemplate jdbc = jdbc();
        DeliveryAttemptRepository attempts = new DeliveryAttemptRepository(jdbc);
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW);
        transaction(jdbc).executeWithoutResult(status -> attempts.insertStarted(
                taskId, 1, NOW, UUID.randomUUID().toString()));
        assertThrows(IllegalStateException.class, () -> transaction(jdbc).execute(status -> {
            assertEquals(1, attempts.finish(attempts.lockStarted(taskId, 1).orElseThrow(),
                    SingleDeliveryHttpResult.localFailure(), NOW.plusSeconds(1), 1000));
            throw new IllegalStateException("rollback");
        }));
        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT result FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
        assertEquals(Integer.valueOf(1), transaction(jdbc).execute(status -> attempts.finish(
                attempts.lockStarted(taskId, 1).orElseThrow(),
                SingleDeliveryHttpResult.unknown(), NOW.plusSeconds(2), 2000)));
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT result FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
        assertEquals("NETWORK_RESULT_UNKNOWN", jdbc.queryForObject(
                "SELECT error_code FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
        assertNull(jdbc.queryForObject(
                "SELECT http_status FROM delivery_attempt WHERE task_id = ?", Integer.class, taskId));
        assertNull(jdbc.queryForObject(
                "SELECT response_excerpt FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
        assertThrows(IllegalArgumentException.class, () -> transaction(jdbc).execute(status ->
                attempts.finish(new DeliveryAttemptRepository.LockedAttempt(1, taskId, 1, NOW),
                        SingleDeliveryHttpResult.fromHttpResponse(200, "ok"), NOW.minusSeconds(1), 0)));
    }

    @Test
    void finishedAttemptUsesP5ClassificationAndFixedSafeErrorText() {
        JdbcTemplate jdbc = jdbc();
        DeliveryAttemptRepository attempts = new DeliveryAttemptRepository(jdbc);
        long endpointId = insertEndpoint(jdbc);
        List<SingleDeliveryHttpResult> outcomes = List.of(
                SingleDeliveryHttpResult.fromHttpResponse(200, "ok"),
                SingleDeliveryHttpResult.fromHttpResponse(404, "not found"),
                SingleDeliveryHttpResult.localFailure());
        List<String> expectedCodes = java.util.Arrays.asList(null, "HTTP_NON_SUCCESS", "LOCAL_REQUEST_REJECTED");
        for (int i = 0; i < outcomes.size(); i++) {
            long taskId = insertTask(jdbc, endpointId, NOW);
            transaction(jdbc).executeWithoutResult(status -> attempts.insertStarted(
                    taskId, 1, NOW, UUID.randomUUID().toString()));
            SingleDeliveryHttpResult outcome = outcomes.get(i);
            assertEquals(Integer.valueOf(1), transaction(jdbc).execute(status -> attempts.finish(
                    attempts.lockStarted(taskId, 1).orElseThrow(), outcome, NOW.plusSeconds(1), 1000)));
            assertEquals(outcome.classification().result().name(), jdbc.queryForObject(
                    "SELECT result FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertEquals(expectedCodes.get(i), jdbc.queryForObject(
                    "SELECT error_code FROM delivery_attempt WHERE task_id = ?", String.class, taskId));
            assertEquals(outcome.httpStatus(), jdbc.queryForObject(
                    "SELECT http_status FROM delivery_attempt WHERE task_id = ?", Integer.class, taskId));
        }
    }

    @Test
    void readsCandidateWithoutWaitingForTaskRowLock() throws Exception {
        JdbcTemplate jdbc = jdbc();
        long taskId = insertTask(jdbc, insertEndpoint(jdbc), NOW.minusDays(1));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> locker = threads.submit(() -> transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM delivery_task WHERE id = ? FOR UPDATE",
                        Long.class, taskId);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试行锁未及时释放");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试行锁等待被中断", exception);
                }
                return null;
            }));
            try {
                assertEquals(true, locked.await(5, TimeUnit.SECONDS));
                Future<List<Long>> scan = threads.submit(
                        () -> new DueDeliveryTaskRepository(jdbc).findDueCandidateIds(NOW));
                assertEquals(taskId, scan.get(5, TimeUnit.SECONDS).getFirst());
                long eventPk = jdbc.queryForObject(
                        "SELECT event_pk FROM delivery_task WHERE id = ?", Long.class, taskId);
                Future<Long> locate = threads.submit(() ->
                        new DueDeliveryTaskRepository(jdbc).findEventPk(taskId).orElseThrow());
                assertEquals(eventPk, locate.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            locker.get(5, TimeUnit.SECONDS);
        }
    }

    private static JdbcTemplate jdbc() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    private static TransactionTemplate transaction(JdbcTemplate jdbc) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static long insertEndpoint(JdbcTemplate jdbc) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(
                Base64.getEncoder().encodeToString(key));
        String name = "due-scan-" + UUID.randomUUID();
        jdbc.update("INSERT INTO endpoint (name, target_type, url, timeout_ms, enabled, "
                        + "signing_secret_ciphertext, created_at, updated_at) "
                        + "VALUES (?, 'SIMULATOR', NULL, 5000, 1, ?, ?, ?)",
                name, cipher.encrypt(cipher.generateSecret()), NOW, NOW);
        return jdbc.queryForObject("SELECT id FROM endpoint WHERE name = ?", Long.class, name);
    }

    private static long insertTask(JdbcTemplate jdbc, long endpointId, LocalDateTime dueAt) {
        String eventId = "due-scan-event-" + UUID.randomUUID();
        jdbc.update("INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                        + "endpoint_id, latest_round_no, current_status, created_at) "
                        + "VALUES (?, 'order.paid', 'null', UNHEX(SHA2('null', 256)), ?, 1, 'PENDING', ?)",
                eventId, endpointId, NOW.minusHours(1));
        long eventPk = jdbc.queryForObject("SELECT id FROM event WHERE event_id = ?", Long.class, eventId);
        jdbc.update("INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                        + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                        + "target_type_snapshot, timeout_ms_snapshot, secret_ciphertext_snapshot, "
                        + "created_at, updated_at) "
                        + "SELECT ?, 1, 'PENDING', 0, 3, 10, 30, ?, 'SIMULATOR', 5000, "
                        + "signing_secret_ciphertext, ?, ? FROM endpoint WHERE id = ?",
                eventPk, dueAt, NOW.minusHours(1), NOW.minusHours(1), endpointId);
        return jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk = ?", Long.class, eventPk);
    }
}
