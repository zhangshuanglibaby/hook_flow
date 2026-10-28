package com.hookflow; // 放在应用根包，便于启动完整后端。

import static org.junit.jupiter.api.Assertions.assertArrayEquals; // 核对 Token 的数据库摘要。
import static org.junit.jupiter.api.Assertions.assertEquals; // 核对状态、字段和数据库记录。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 核对密码不是明文。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对 Token 和到期时间。
import java.net.URI; // 构造本机随机端口上的登录地址。
import java.net.http.HttpClient; // 发送真实 HTTP 请求。
import java.net.http.HttpRequest; // 构造 JSON POST 请求。
import java.net.http.HttpResponse; // 读取实际 HTTP 响应。
import java.nio.charset.StandardCharsets; // 按 ASCII 计算 Token 摘要。
import java.security.MessageDigest; // 独立计算 SHA-256 预期值。
import java.security.SecureRandom; // 为测试启动生成随机的 32 字节主密钥。
import java.util.Base64; // 将随机字节编码为配置可读取的文本。
import java.time.Instant; // 检查响应中的 UTC 到期时间。
import java.time.LocalDateTime; // 核对数据库保存的 UTC 时间。
import java.time.ZoneOffset; // 明确数据库时间使用 UTC。
import org.junit.jupiter.api.Test; // 标记集成测试。
import org.springframework.boot.builder.SpringApplicationBuilder; // 可关闭并重新启动完整应用。
import org.springframework.context.ConfigurableApplicationContext; // 管理应用上下文的关闭。
import org.springframework.jdbc.core.JdbcTemplate; // 查询实际 MySQL 内容。
import org.testcontainers.junit.jupiter.Container; // 管理临时数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用容器测试扩展。
import org.testcontainers.mysql.MySQLContainer; // 使用项目约定的 MySQL 版本。
import tools.jackson.databind.JsonNode; // 结构化读取 HTTP JSON 响应。
import tools.jackson.databind.json.JsonMapper; // 使用应用实际配置的 JSON 映射器。
import com.hookflow.auth.application.AdminLoginService; // 重启后校验已有 Token。

@Testcontainers // 测试开始前启动 MySQL，结束后清理。
class AdminLoginHttpIntegrationTests { // 验证 P2-01 的实际启动与 HTTP 登录。
    @Container // 容器在两次应用启动之间保持运行。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 使用真实 MySQL 8.4.13。

    @Test // 同一测试依次经历首次启动和应用重启。
    void loginOverHttpAndKeepTokenAfterRestart() throws Exception { // HTTP、数据库和摘要操作失败时使测试失败。
        String token; // 保存首次登录签发的 Token，仅在测试内存中使用。
        String originalPasswordHash; // 保存首次启动后数据库里的密码摘要。
        try (ConfigurableApplicationContext first = start("demo-admin", "test-password-one")) { // 首次启动并在结束时关闭应用。
            JsonMapper mapper = first.getBean(JsonMapper.class); // 使用正式应用的 JSON 配置。
            HttpResponse<String> success = post(loginUri(first), "{\"username\":\"demo-admin\",\"password\":\"test-password-one\"}"); // 用正确密码请求登录。
            assertEquals(200, success.statusCode()); // 正确密码必须返回 HTTP 200。
            JsonNode body = mapper.readTree(success.body()); // 结构化解析成功响应。
            assertEquals(2, body.size()); // 响应只包含 token 和 expiresAt。
            token = body.path("token").asString(); // 读取仅在这次响应出现的明文 Token。
            assertEquals(43, token.length()); // 32 字节高熵随机值应编码为 43 字符。
            Instant expiresAt = Instant.parse(body.path("expiresAt").asString()); // 解析带 Z 的 UTC 到期时间。
            assertTrue(expiresAt.isAfter(Instant.now())); // 刚签发的 Token 尚未到期。

            HttpResponse<String> wrong = post(loginUri(first), "{\"username\":\"demo-admin\",\"password\":\"wrong-password\"}"); // 用错误密码发起请求。
            assertEquals(401, wrong.statusCode()); // 密码错误必须返回 HTTP 401。
            JsonNode error = mapper.readTree(wrong.body()); // 读取统一错误响应。
            assertEquals("UNAUTHORIZED", error.path("code").asString()); // 错误代码必须符合 P0 契约。
            assertEquals(wrong.headers().firstValue("X-Request-Id").orElse(""), error.path("requestId").asString()); // 响应体与响应头使用同一个请求 ID。
            HttpResponse<String> invalid = post(loginUri(first), "{\"username\":\"demo-admin\",\"password\":\"test-password-one\",\"unexpected\":true}"); // 额外字段属于非法请求。
            assertEquals(400, invalid.statusCode()); // 未知字段必须返回 HTTP 400。
            assertEquals("INVALID_REQUEST", mapper.readTree(invalid.body()).path("code").asString()); // 校验失败使用统一错误代码。

            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class); // 查询应用连接的同一个临时 MySQL。
            originalPasswordHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, "demo-admin"); // 读取持久化的密码摘要。
            assertNotEquals("test-password-one", originalPasswordHash); // 数据库不能保存初始明文密码。
            byte[] savedHash = jdbc.queryForObject("SELECT token_hash FROM admin_session", (rs, row) -> rs.getBytes(1)); // 读取唯一会话的二进制哈希。
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)), savedHash); // 数据库只保存签发 Token 的 SHA-256 摘要。
            LocalDateTime savedExpiry = jdbc.queryForObject("SELECT expires_at FROM admin_session", (rs, row) -> rs.getObject(1, LocalDateTime.class)); // 读取真实到期字段。
            assertEquals(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), savedExpiry); // 数据库到期时间与响应一致，按 UTC 保存。
        } // 关闭首次启动的 HTTP 服务和 Spring 容器，数据库容器继续运行。

        try (ConfigurableApplicationContext second = start("another-admin", "test-password-two")) { // 改变初始环境变量并重新启动应用。
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class); // 从新应用上下文读取同一数据库。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class)); // 重启不能创建第二个管理员。
            assertEquals(originalPasswordHash, jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, "demo-admin")); // 重启不能重置原密码。
            assertTrue(second.getBean(AdminLoginService.class).validateToken(token).isPresent()); // 重启后的服务仍从数据库验证首次签发的未到期 Token。
            assertEquals(401, post(loginUri(second), "{\"username\":\"another-admin\",\"password\":\"test-password-two\"}").statusCode()); // 新环境变量不能替代原账户。
            assertEquals(200, post(loginUri(second), "{\"username\":\"demo-admin\",\"password\":\"test-password-one\"}").statusCode()); // 原密码在重启后仍可登录。
        } // 关闭第二次启动的应用。
    } // 完整登录与重启测试结束。

    private ConfigurableApplicationContext start(String username, String password) { // 使用临时数据库启动完整应用。
        byte[] keyBytes = new byte[32]; // 准备 AES-256 所需长度的主密钥。
        new SecureRandom().nextBytes(keyBytes); // 每次启动生成随机测试密钥，不保存固定值。
        String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 编码为环境配置使用的 Base64。
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动实际后端。
                "--spring.profiles.active=p2-login-test", // 使用真实数据库测试配置。
                "--server.port=0", // 由系统分配测试端口。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本测试的 MySQL 容器。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器数据库用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器数据库密码。
                "--HOOKFLOW_ADMIN_USERNAME=" + username, // 设置本次启动的初始管理员用户名。
                "--HOOKFLOW_ADMIN_PASSWORD=" + password, // 设置本次启动的初始管理员密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey // 仅向本次测试进程提供随机主密钥。
        ); // 完成应用启动。
    } // 测试启动方法结束。

    private URI loginUri(ConfigurableApplicationContext context) { // 查找本次启动实际使用的端口。
        String port = context.getEnvironment().getRequiredProperty("local.server.port"); // 从当前应用上下文读取随机端口。
        return URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"); // 只请求本机测试服务器。
    } // 登录地址方法结束。

    private HttpResponse<String> post(URI uri, String json) throws Exception { // 向实际网络接口提交测试 JSON。
        HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json") // 标明 JSON 请求体。
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(); // 构造一次 POST 请求。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 真正发送请求并读取响应。
    } // HTTP 请求方法结束。
} // 集成测试类结束。