package com.hookflow.endpoint.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.HookflowBackendApplication;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class EndpointClaimGuardIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @Test
    void requiresCallerTransactionAndChecksCurrentEndpointState() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            EndpointClaimGuard guard = context.getBean(EndpointClaimGuard.class);
            long actor = actor(jdbc);
            EndpointSecretProvisioningService provisioning =
                    context.getBean(EndpointSecretProvisioningService.class);
            EndpointConfiguration enabled = provisioning.createSimulator(
                    "claim-enabled-" + UUID.randomUUID(), 5000, true, actor, UUID.randomUUID().toString());
            EndpointConfiguration disabled = provisioning.createSimulator(
                    "claim-disabled-" + UUID.randomUUID(), 5000, false, actor, UUID.randomUUID().toString());

            assertThrows(IllegalTransactionStateException.class, () -> guard.lockEnabled(enabled.id()));
            TransactionTemplate transaction = transaction(context);
            assertEquals(Boolean.TRUE, transaction.execute(status -> guard.lockEnabled(enabled.id())));
            assertEquals(Boolean.FALSE, transaction.execute(status -> guard.lockEnabled(disabled.id())));
            assertEquals(Boolean.FALSE, transaction.execute(status -> guard.lockEnabled(Long.MAX_VALUE)));
            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", enabled.id());
            assertEquals(Boolean.FALSE, transaction.execute(status -> guard.lockEnabled(enabled.id())));
        }
    }

    @Test
    void holdsEndpointRowLockUntilCallerTransactionEnds() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            long actor = actor(jdbc);
            long endpointId = context.getBean(EndpointSecretProvisioningService.class)
                    .createSimulator("claim-lock-" + UUID.randomUUID(), 5000, actor, UUID.randomUUID().toString())
                    .id();
            EndpointClaimGuard guard = context.getBean(EndpointClaimGuard.class);
            TransactionTemplate transaction = transaction(context);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = threads.submit(() -> transaction.execute(status -> {
                    if (!guard.lockEnabled(endpointId)) {
                        throw new AssertionError("接收端应处于启用状态");
                    }
                    locked.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("测试行锁未及时释放");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return null;
                }));
                try {
                    assertTrue(locked.await(5, TimeUnit.SECONDS));
                    Future<Integer> disable = threads.submit(() ->
                            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", endpointId));
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> disable.get(250, TimeUnit.MILLISECONDS));
                    release.countDown();
                    holder.get(5, TimeUnit.SECONDS);
                    assertEquals(1, disable.get(5, TimeUnit.SECONDS));
                    assertEquals(Boolean.FALSE, transaction.execute(status -> guard.lockEnabled(endpointId)));
                } finally {
                    release.countDown();
                }
            }
        }
    }

    private static long actor(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'claim-guard-test-admin'",
                Long.class);
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static ConfigurableApplicationContext start() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test",
                "--server.port=0",
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=claim-guard-test-admin",
                "--HOOKFLOW_ADMIN_PASSWORD=claim-guard-test-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(key));
    }
}
