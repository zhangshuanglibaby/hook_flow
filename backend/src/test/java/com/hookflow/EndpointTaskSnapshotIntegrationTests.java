package com.hookflow; // 测试类位于应用根包，以便启动完整后端。

import static org.junit.jupiter.api.Assertions.assertArrayEquals; // 比较编辑前后的密文字节。
import static org.junit.jupiter.api.Assertions.assertEquals; // 核对配置、解密结果和数据库行数。
import static org.junit.jupiter.api.Assertions.assertThrows; // 核对事务外调用被拒绝。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对不存在的接收端返回空结果。
import java.security.SecureRandom; // 为测试启动生成随机主密钥。
import java.util.Base64; // 将主密钥编码为标准 Base64。
import java.util.UUID; // 为审计生成不同请求 ID。
import org.junit.jupiter.api.Test; // 标记集成测试。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动真实应用。
import org.springframework.context.ConfigurableApplicationContext; // 在测试结束时关闭应用。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实 MySQL 数据。
import org.springframework.transaction.IllegalTransactionStateException; // 识别缺少调用方事务的错误。
import org.springframework.transaction.PlatformTransactionManager; // 取得应用实际使用的事务管理器。
import org.springframework.transaction.TransactionDefinition; // 指定约定的 READ COMMITTED 隔离级别。
import org.springframework.transaction.support.TransactionTemplate; // 模拟未来调用方的短事务。
import org.testcontainers.junit.jupiter.Container; // 管理临时数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers。
import org.testcontainers.mysql.MySQLContainer; // 使用与现有测试一致的 MySQL 版本。
import com.hookflow.endpoint.application.EndpointSecretProvisioningService; // 安全创建内部模拟器接收端。
import com.hookflow.endpoint.application.EndpointTaskSnapshotService; // 调用带事务要求的快照入口。
import com.hookflow.endpoint.application.EndpointTaskSnapshotService.EndpointDisabledException; // 识别持锁复查时发现的接收端停用。
import com.hookflow.endpoint.application.EndpointUpdateService; // 编辑名称和超时。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 取得新接收端的 ID。
import com.hookflow.endpoint.domain.EndpointTaskSnapshot; // 检查事务内形成的快照。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 验证密文可供未来投递侧解密。

@Testcontainers // 测试期间启动并清理真实 MySQL。
class EndpointTaskSnapshotIntegrationTests { // 只测试应用服务与数据库，不发送 HTTP 请求。
    @Container // 交由 JUnit 管理容器生命周期。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 沿用现有数据库测试版本。

    @Test // 验证快照事务边界及编辑后旧快照不变。
    void locksAndKeepsCiphertextSnapshotAfterEdit() { // 使用真实应用和真实数据库执行。
        try (ConfigurableApplicationContext context = start()) { // 确保测试结束时关闭应用。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 查询本次测试的 MySQL。
            EndpointSecretProvisioningService provisioning = context.getBean(EndpointSecretProvisioningService.class); // 取得安全创建服务。
            EndpointTaskSnapshotService snapshots = context.getBean(EndpointTaskSnapshotService.class); // 取得带事务代理的快照服务。
            EndpointUpdateService updates = context.getBean(EndpointUpdateService.class); // 取得现有编辑服务。
            EndpointSigningSecretCipher cipher = context.getBean(EndpointSigningSecretCipher.class); // 取得本次启动使用的加密组件。
            Long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "snapshot-test-admin"); // 查询真实管理员 ID。
            EndpointConfiguration created = provisioning.createSimulator("snapshot-test", 5000, actor, UUID.randomUUID().toString()); // 创建带独立密文的模拟器接收端。
            assertThrows(IllegalTransactionStateException.class, () -> snapshots.lockEnabledSnapshot(created.id())); // 没有调用方事务时必须拒绝加锁读取。

            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); // 建立测试用调用方事务。
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); // 遵守 P0-03 的写事务隔离约定。
            assertTrue(Boolean.TRUE.equals(transaction.execute(status -> snapshots.lockEnabledSnapshot(Long.MAX_VALUE).isEmpty()))); // 将事务返回的 Boolean 明确转换为布尔判断，确认不存在的接收端返回空结果。
            EndpointTaskSnapshot before = transaction.execute(status -> snapshots.lockEnabledSnapshot(created.id()).orElseThrow()); // 在事务内锁行并复制第一次快照。
            byte[] originalCiphertext = before.secretCiphertextSnapshot(); // 只保存复制出的密文字节。
            String originalSecret = cipher.decryptForDelivery(originalCiphertext); // 仅在测试中验证快照密文可解密。

            EndpointConfiguration updated = updates.update(created.id(), "snapshot-renamed", 7000, actor, UUID.randomUUID().toString()).orElseThrow(); // 通过现有事务服务编辑配置。
            assertEquals(7000, updated.timeoutMs()); // 确认新配置的超时已改变。
            EndpointTaskSnapshot after = transaction.execute(status -> snapshots.lockEnabledSnapshot(created.id()).orElseThrow()); // 新事务取得编辑后的快照。
            assertEquals(5000, before.timeoutMs()); // 编辑不能改变之前取得的快照。
            assertEquals(7000, after.timeoutMs()); // 编辑后的新快照应取得新超时。
            assertArrayEquals(originalCiphertext, before.secretCiphertextSnapshot()); // 旧快照密文保持原样。
            assertArrayEquals(originalCiphertext, after.secretCiphertextSnapshot()); // 只编辑名称和超时不会轮换接收端密钥。
            assertEquals(originalSecret, cipher.decryptForDelivery(after.secretCiphertextSnapshot())); // 新快照仍使用同一接收端的独立密钥。
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ENDPOINT_UPDATED' AND object_id = ?", Long.class, Long.toString(created.id()))); // 成功编辑应留下审计。
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class)); // 本任务尚未创建或持久化任何投递任务。
        } // 关闭应用；JUnit 负责清理数据库容器。
    } // 快照编辑集成测试结束。

    @Test // 验证停用状态及损坏密文在真实数据库上的失败路径。
    void rejectsDisabledEndpointAndDamagedStoredCiphertext() { // 只在临时测试数据库中故意修改状态和密文。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用并确保关闭。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 取得本次测试使用的真实 MySQL。
            EndpointSecretProvisioningService provisioning = context.getBean(EndpointSecretProvisioningService.class); // 通过安全内部服务创建接收端。
            EndpointTaskSnapshotService snapshots = context.getBean(EndpointTaskSnapshotService.class); // 取得带事务代理的快照入口。
            EndpointSigningSecretCipher cipher = context.getBean(EndpointSigningSecretCipher.class); // 取得投递侧解密接口。
            Long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "snapshot-test-admin"); // 取得测试管理员 ID。
            EndpointConfiguration created = provisioning.createSimulator("failure-test", 5000, actor, UUID.randomUUID().toString()); // 用真实随机密钥及密文创建模拟器接收端。
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); // 模拟未来调用方事务。
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); // 使用 P0-03 规定的隔离级别。

            jdbc.update("UPDATE endpoint SET enabled = 0 WHERE id = ?", created.id()); // 仅在测试中模拟接收端已被停用。
            assertThrows(EndpointDisabledException.class, () -> transaction.execute(status -> snapshots.lockEnabledSnapshot(created.id()))); // 持锁复查必须拒绝停用接收端。
            jdbc.update("UPDATE endpoint SET enabled = 1 WHERE id = ?", created.id()); // 恢复测试数据状态，以便单独验证密文损坏。
            EndpointTaskSnapshot valid = transaction.execute(status -> snapshots.lockEnabledSnapshot(created.id()).orElseThrow()); // 先取得真实的、可用的密文快照。
            byte[] damaged = valid.secretCiphertextSnapshot(); // 复制密文，保留原快照不变。
            damaged[damaged.length - 1] ^= 1; // 仅在测试中破坏认证标签的一个比特。
            jdbc.update("UPDATE endpoint SET signing_secret_ciphertext = ? WHERE id = ?", damaged, created.id()); // 将故意损坏的密文写回临时数据库。
            EndpointTaskSnapshot damagedSnapshot = transaction.execute(status -> snapshots.lockEnabledSnapshot(created.id()).orElseThrow()); // 快照阶段只复制密文，不提前解密。
            assertEquals("签名密钥密文无效", assertThrows(IllegalStateException.class, () -> cipher.decryptForDelivery(damagedSnapshot.secretCiphertextSnapshot())).getMessage()); // 投递侧必须明确失败，不能回退到明文。
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task", Long.class)); // 失败路径不得被误认为已创建任务。
        } // 关闭应用；JUnit 清理临时 MySQL。
    } // 停用及密文损坏测试结束。

    private ConfigurableApplicationContext start() { // 使用临时数据库及随机主密钥启动正式配置。
        byte[] keyBytes = new byte[32]; // 准备符合主密钥长度要求的字节数组。
        new SecureRandom().nextBytes(keyBytes); // 每次测试启动使用不同随机值。
        String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 转换成规范的标准 Base64。
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动真实应用服务。
                "--spring.profiles.active=p2-login-test", // 使用现有真实数据库测试配置。
                "--server.port=0", // 使用随机端口，但本测试不发送 HTTP 请求。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本测试的临时 MySQL。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器提供的数据库用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器提供的数据库密码。
                "--HOOKFLOW_ADMIN_USERNAME=snapshot-test-admin", // 初始化本测试的管理员。
                "--HOOKFLOW_ADMIN_PASSWORD=snapshot-test-password", // 使用仅供测试的管理员密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey); // 配置本次启动专用的加密主密钥。
    } // 测试应用启动方法结束。
} // 真实 MySQL 快照集成测试类结束。