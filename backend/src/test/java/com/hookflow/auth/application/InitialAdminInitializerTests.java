package com.hookflow.auth.application; // 测试认证应用层的首次初始化行为。

import static org.junit.jupiter.api.Assertions.assertEquals; // 核对账户数量和原有摘要。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对更换后的初始密码不能登录。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 核对数据库没有保存明文密码。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对原始密码能够匹配摘要。
import java.time.Clock; // 构造固定时间供初始化器使用。
import java.time.Instant; // 指定测试中的固定瞬间。
import java.time.ZoneOffset; // 固定时钟使用 UTC。
import org.flywaydb.core.Flyway; // 对一次性真实数据库执行现有迁移。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.boot.DefaultApplicationArguments; // 模拟应用启动运行器的参数。
import org.springframework.jdbc.core.JdbcTemplate; // 查询测试数据库中的真实记录。
import org.springframework.jdbc.datasource.DriverManagerDataSource; // 为测试创建简单 JDBC 数据源。
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; // 使用正式配置相同的密码编码实现。
import org.springframework.security.crypto.password.PasswordEncoder; // 通过编码器接口验证摘要。
import org.testcontainers.junit.jupiter.Container; // 管理本测试的 MySQL 容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers 测试扩展。
import org.testcontainers.mysql.MySQLContainer; // 创建 MySQL 8.4.13 测试容器。
import com.hookflow.auth.infrastructure.AdminUserRepository; // 测试真实的管理员数据库访问类。

@Testcontainers // 测试前启动一次性数据库，结束后自动清理。
class InitialAdminInitializerTests { // 验证首次初始化与重复启动的数据库行为。
    @Container // 此容器只属于本测试类。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 与现有迁移测试使用同一数据库版本。

    @Test // 执行一次完整的管理员初始化测试。
    void createsOnlyFirstAdminWithoutSavingPlaintext() { // 先初始化，再模拟配置改变后的重复启动。
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()) // 使用容器提供的临时连接信息。
                .locations("classpath:db/migration").load().migrate(); // 在空数据库上执行项目现有的 Flyway 迁移。
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); // 连接同一个测试数据库。
        JdbcTemplate jdbc = new JdbcTemplate(dataSource); // 创建查询和写入所需的 Spring JDBC 对象。
        AdminUserRepository users = new AdminUserRepository(jdbc); // 使用正式代码中的真实 SQL。
        PasswordEncoder encoder = new BCryptPasswordEncoder(); // 使用正式配置选定的 BCrypt 算法。
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC); // 避免测试依赖当前时间。
        var arguments = new DefaultApplicationArguments(new String[0]); // 提供空的应用启动参数。

        new InitialAdminInitializer(users, encoder, clock, "demo-admin", "test-password-one").run(arguments); // 模拟首次启动。
        String originalHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, "demo-admin"); // 从真实表读取摘要。
        assertNotEquals("test-password-one", originalHash); // 数据库字段不能等于明文密码。
        assertTrue(encoder.matches("test-password-one", originalHash)); // 首次提供的密码应能匹配摘要。

        new InitialAdminInitializer(users, encoder, clock, "another-admin", "test-password-two").run(arguments); // 用不同初始配置模拟重复启动。
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)); // 数据库仍只有一个管理员。
        assertEquals(originalHash, jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, "demo-admin")); // 原密码摘要不能被重置。
        assertFalse(encoder.matches("test-password-two", originalHash)); // 新环境变量密码不能替换原密码。
    } // 测试方法结束。
} // 测试类结束。