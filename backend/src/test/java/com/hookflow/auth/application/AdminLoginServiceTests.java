package com.hookflow.auth.application; // 测试认证模块应用层。

import static org.junit.jupiter.api.Assertions.assertArrayEquals; // 核对数据库保存的是 Token 摘要。
import static org.junit.jupiter.api.Assertions.assertEquals; // 核对数量和到期时间。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对无效或过期 Token。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 核对密码不是明文。
import static org.junit.jupiter.api.Assertions.assertThrows; // 核对错误凭据被拒绝。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对有效 Token。
import java.nio.charset.StandardCharsets; // 明确计算摘要时使用 ASCII。
import java.security.MessageDigest; // 独立计算预期的 SHA-256 摘要。
import java.time.Duration; // 推进测试时钟至到期时刻。
import java.time.Instant; // 固定测试中的签发时间。
import org.flywaydb.core.Flyway; // 对真实 MySQL 执行现有迁移。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.boot.DefaultApplicationArguments; // 模拟首次启动参数。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实数据库记录。
import org.springframework.jdbc.datasource.DriverManagerDataSource; // 创建数据库连接。
import org.springframework.security.authentication.BadCredentialsException; // 核对错误凭据异常。
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; // 使用正式配置相同的密码算法。
import org.testcontainers.junit.jupiter.Container; // 管理测试数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers。
import org.testcontainers.mysql.MySQLContainer; // 使用 MySQL 8.4.13。
import com.hookflow.auth.infrastructure.AdminSessionRepository; // 使用正式会话仓库。
import com.hookflow.auth.infrastructure.AdminUserRepository; // 使用正式管理员仓库。
import com.hookflow.support.MutableClock; // 无需等待即可控制到期时间。

@Testcontainers // 测试期间启动并清理真实数据库。
class AdminLoginServiceTests { // 验证登录服务与数据库的实际交互。
    @Container // 让测试扩展管理此容器。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 沿用项目已有的数据库版本。

    @Test // 执行正确登录、拒绝错误密码及到期校验。
    void storesOnlyHashesAndValidatesTokenUntilExpiry() throws Exception { // 摘要算法查找可能报告受检异常。
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()) // 使用容器的临时数据库凭据。
                .locations("classpath:db/migration").load().migrate(); // 执行项目真实的 V1 迁移。
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); // 连接已迁移的数据库。
        JdbcTemplate jdbc = new JdbcTemplate(dataSource); // 用于创建仓库并读取断言数据。
        var users = new AdminUserRepository(jdbc); // 使用生产代码中的管理员 SQL。
        var sessions = new AdminSessionRepository(jdbc); // 使用生产代码中的会话 SQL。
        var encoder = new BCryptPasswordEncoder(); // 与正式密码编码器保持一致。
        var clock = new MutableClock(Instant.parse("2026-10-28T08:00:00Z")); // 固定 UTC 签发时间。
        new InitialAdminInitializer(users, encoder, clock, "demo-admin", "test-password-one") // 提供仅供测试使用的初始凭据。
                .run(new DefaultApplicationArguments(new String[0])); // 模拟首次启动创建账户。
        var service = new AdminLoginService(users, sessions, encoder, clock); // 创建待测试的登录服务。

        assertThrows(BadCredentialsException.class, () -> service.login("demo-admin", "wrong-password")); // 错误密码不能签发 Token。
        assertThrows(BadCredentialsException.class, () -> service.login("missing-admin", "test-password-one")); // 不存在的账号也不能签发 Token。
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM admin_session", Long.class)); // 两次失败都没有留下会话。

        var result = service.login("demo-admin", "test-password-one"); // 正确密码应成功签发 Token。
        assertEquals(43, result.token().length()); // 32 字节随机数的无填充 Base64 URL 文本应为 43 字符。
        assertEquals(Instant.parse("2026-10-28T08:15:00Z"), result.expiresAt()); // Token 有效期为 15 分钟。
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM admin_session", Long.class)); // 成功登录只创建一条会话。
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, "demo-admin"); // 读取保存的密码摘要。
        assertNotEquals("test-password-one", passwordHash); // 数据库中不能保存明文密码。
        assertTrue(encoder.matches("test-password-one", passwordHash)); // 保存的摘要仍可验证正确密码。
        byte[] storedTokenHash = jdbc.queryForObject("SELECT token_hash FROM admin_session", (rs, rowNum) -> rs.getBytes(1)); // 读取数据库中的二进制摘要。
        assertEquals(32, storedTokenHash.length); // SHA-256 摘要应为 32 字节。
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(result.token().getBytes(StandardCharsets.US_ASCII)), storedTokenHash); // 数据库存的是 Token 哈希而非原文。

        var reopenedJdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())); // 用新连接模拟重新打开数据访问层。
        var restartedService = new AdminLoginService(new AdminUserRepository(reopenedJdbc), new AdminSessionRepository(reopenedJdbc), encoder, clock); // 重建服务对象并继续使用原数据库。
        assertTrue(restartedService.validateToken(result.token()).isPresent()); // 重建服务对象后，原 Token 仍由数据库验证。
        assertFalse(restartedService.validateToken("invalid-token").isPresent()); // 格式错误的 Token 无效。
        clock.advance(Duration.ofMinutes(15)); // 将时钟推进到确切到期时刻。
        assertFalse(restartedService.validateToken(result.token()).isPresent()); // 到期时原 Token 必须失效。
    } // 测试方法结束。
} // 测试类结束。