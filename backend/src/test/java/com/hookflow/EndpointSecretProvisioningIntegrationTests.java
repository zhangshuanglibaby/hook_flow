package com.hookflow; // 测试类放在应用根包，启动完整后端。

import static org.junit.jupiter.api.Assertions.assertEquals; // 核对数据库值和审计内容。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对审计不含明文密钥。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 核对两个接收端的密钥不同。
import static org.junit.jupiter.api.Assertions.assertNull; // 核对模拟器没有外部 URL。
import static org.junit.jupiter.api.Assertions.assertThrows; // 核对审计失败时事务回滚。
import java.security.SecureRandom; // 生成每次测试启动专用的主密钥。
import java.time.LocalDateTime; // 比较数据库中的创建和审计时间。
import java.util.Base64; // 将测试主密钥转换为标准 Base64。
import java.util.Map; // 读取数据库中的审计字段。
import java.util.UUID; // 生成符合现有审计约定的请求 ID。
import org.junit.jupiter.api.Test; // 标记集成测试方法。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动真实应用服务。
import org.springframework.context.ConfigurableApplicationContext; // 结束测试时关闭应用。
import org.springframework.dao.DataIntegrityViolationException; // 识别真实 MySQL 外键失败。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实 MySQL 中的接收端和审计行。
import org.testcontainers.junit.jupiter.Container; // 管理测试数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers 扩展。
import org.testcontainers.mysql.MySQLContainer; // 使用现有测试约定的 MySQL 版本。
import com.hookflow.endpoint.application.EndpointSecretProvisioningService; // 调用带事务代理的内部创建服务。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 检查返回值只含普通配置。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 验证数据库密文可在投递边界解密。

@Testcontainers // 为本测试类启动并清理临时 MySQL。
class EndpointSecretProvisioningIntegrationTests { // 只验证应用服务和数据库，不验证 HTTP。
    @Container // 容器由 JUnit 扩展管理。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 与现有集成测试保持一致。

    @Test // 核对两个模拟器接收端分别生成密文并记录成功审计。
    void storesIndependentCiphertextsAndAuditsCreation() { // 在真实 Flyway V1 数据库中执行。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用并在结束时关闭。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 取得当前 MySQL 的查询组件。
            EndpointSecretProvisioningService service = context.getBean(EndpointSecretProvisioningService.class); // 通过 Spring 代理调用真实事务。
            EndpointSigningSecretCipher cipher = context.getBean(EndpointSigningSecretCipher.class); // 使用本次启动的真实环境主密钥。
            Long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "secret-test-admin"); // 取得已有测试管理员 ID。
            String firstRequestId = UUID.randomUUID().toString(); // 为第一次创建生成审计请求 ID。
            String secondRequestId = UUID.randomUUID().toString(); // 为第二次创建生成不同请求 ID。
            EndpointConfiguration first = service.createSimulator("simulator-one", 5000, actor, firstRequestId); // 创建第一个内部模拟器接收端。
            EndpointConfiguration second = service.createSimulator("simulator-two", 7000, actor, secondRequestId); // 创建第二个内部模拟器接收端。
            assertNotEquals(first.id(), second.id()); // 两次创建必须得到不同数据库主键。
            assertEquals("SIMULATOR", first.targetType().name()); // 创建结果只能是模拟器类型。
            assertNull(first.url()); // 模拟器不得拥有外部 URL。
            assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class)); // 数据库确实保存两条接收端。
            byte[] firstStored = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", (rs, rowNum) -> rs.getBytes(1), first.id()); // 读取第一条数据库原始密文。
            byte[] secondStored = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", (rs, rowNum) -> rs.getBytes(1), second.id()); // 读取第二条数据库原始密文。
            String firstSecret = cipher.decryptForDelivery(firstStored); // 仅在测试内存中验证第一份密文可解密。
            String secondSecret = cipher.decryptForDelivery(secondStored); // 仅在测试内存中验证第二份密文可解密。
            assertNotEquals(firstSecret, secondSecret); // 两个接收端必须拥有独立的签名密钥。
            assertFalse(first.toString().contains(firstSecret)); // 返回的普通配置不得包含明文密钥。
            assertNull(jdbc.queryForObject("SELECT url FROM endpoint WHERE id = ?", String.class, first.id())); // 数据库模拟器 URL 必须保持 NULL。
            Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, object_type, object_id, occurred_at, request_id FROM audit_log WHERE request_id = ?", firstRequestId); // 读取第一次创建的成功审计。
            assertEquals("ENDPOINT_CREATED", audit.get("action")); // 操作类型应为创建接收端。
            assertEquals("ENDPOINT", audit.get("object_type")); // 审计对象类型采用既有约定。
            assertEquals(Long.toString(first.id()), audit.get("object_id")); // 对象 ID 必须是新建接收端的主键。
            assertEquals(actor.longValue(), ((Number) audit.get("actor_user_id")).longValue()); // 操作者必须是实际管理员。
            assertFalse(audit.toString().contains(firstSecret)); // 审计不得保存解密后的签名密钥。
            LocalDateTime createdAt = jdbc.queryForObject("SELECT created_at FROM endpoint WHERE id = ?", (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), first.id()); // 读取接收端创建时间。
            assertEquals(createdAt, audit.get("occurred_at")); // 创建与审计应使用同一事务的 UTC 毫秒时间。
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ? AND action = 'ENDPOINT_CREATED'", Long.class, secondRequestId)); // 第二次成功创建也应有一条审计。
        } // 关闭应用；测试扩展负责清理数据库容器。
    } // 正常创建测试结束。

    @Test // 核对成功审计插入失败时，密文接收端不会单独留下。
    void auditFailureRollsBackEndpoint() { // 直接验证真实 MySQL 的事务回滚。
        try (ConfigurableApplicationContext context = start()) { // 启动应用并确保关闭。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 查询回滚前后的真实数据库。
            EndpointSecretProvisioningService service = context.getBean(EndpointSecretProvisioningService.class); // 使用带事务代理的应用服务。
            Long beforeEndpoints = jdbc.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class); // 记录操作前的接收端数量。
            Long beforeAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class); // 记录操作前的审计数量。
            assertThrows(DataIntegrityViolationException.class, () -> service.createSimulator("rollback-test", 5000, Long.MAX_VALUE, UUID.randomUUID().toString())); // 不存在的操作者触发审计外键失败。
            assertEquals(beforeEndpoints, jdbc.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class)); // 接收端插入必须随审计失败回滚。
            assertEquals(beforeAudits, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)); // 失败调用不得留下成功审计。
        } // 关闭应用上下文。
    } // 回滚测试结束。

    private ConfigurableApplicationContext start() { // 使用临时数据库和随机主密钥启动正式配置。
        byte[] keyBytes = new byte[32]; // 准备符合主密钥要求的字节长度。
        new SecureRandom().nextBytes(keyBytes); // 每次启动使用新的随机测试主密钥。
        String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 转换成规范标准 Base64。
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动真实后端，但本测试不发 HTTP 请求。
                "--spring.profiles.active=p2-login-test", // 沿用现有数据库集成测试配置，而非 skeleton。
                "--server.port=0", // 避免占用固定端口。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本类专用 MySQL 容器。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器生成的用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器生成的密码。
                "--HOOKFLOW_ADMIN_USERNAME=secret-test-admin", // 首次启动创建测试管理员。
                "--HOOKFLOW_ADMIN_PASSWORD=secret-test-password", // 设置仅用于测试的管理员密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey); // 将随机主密钥只用于本次进程。
    } // 测试应用启动方法结束。
} // 真实 MySQL 与应用服务测试类结束。