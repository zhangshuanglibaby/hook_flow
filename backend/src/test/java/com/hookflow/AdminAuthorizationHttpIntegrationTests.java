package com.hookflow; // 放在应用根包下，确保能够启动完整后端。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较 HTTP 状态和错误字段。
import static org.junit.jupiter.api.Assertions.assertTrue; // 确认测试端点返回成功标记。

import java.net.URI; // 构造随机端口上的 HTTP 地址。
import java.net.http.HttpClient; // 发送真实 HTTP 请求。
import java.net.http.HttpRequest; // 构造 HTTP 请求。
import java.net.http.HttpResponse; // 读取 HTTP 响应。
import java.security.SecureRandom; // 为本次测试启动生成随机主密钥。
import java.util.Base64; // 将随机字节编码为环境配置使用的文本。

import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动完整 Spring 应用。
import org.springframework.context.ConfigurableApplicationContext; // 关闭测试应用上下文。
import org.springframework.jdbc.core.JdbcTemplate; // 通过测试数据库把当前会话设置为已过期。
import org.testcontainers.junit.jupiter.Container; // 管理测试容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers 生命周期。
import org.testcontainers.mysql.MySQLContainer; // 使用真实 MySQL 8.4.13。

import tools.jackson.databind.JsonNode; // 结构化读取 JSON 响应。
import tools.jackson.databind.json.JsonMapper; // 使用应用实际的 JSON 映射器。


@Testcontainers // 让 JUnit 自动启动和清理 MySQL 容器。
class AdminAuthorizationHttpIntegrationTests { // 验证管理员 Token 的 HTTP 权限边界。

    @Container // 声明本测试类使用的 MySQL 容器。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 使用项目约定的真实 MySQL 版本。

    @Test // 执行真实 HTTP 权限边界验证。
    void validAdminTokenCanAccessProtectedEndpointAndMissingTokenCannot() throws Exception { // 覆盖有效 Token 和缺失 Token 两条路径。
        try (ConfigurableApplicationContext context = start()) { // 启动正式数据库配置，并在测试结束时关闭应用。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 使用应用上下文中的 JSON 映射器。

            HttpResponse<String> login = post( // 先通过正式登录接口取得管理员 Token。
                    loginUri(context), // 使用当前应用的随机端口。
                    "{\"username\":\"boundary-admin\",\"password\":\"boundary-password\"}" // 使用测试专用管理员凭据。
            ); // 登录请求结束。

            assertEquals(200, login.statusCode()); // 正确凭据必须返回 HTTP 200。

            String token = mapper.readTree(login.body()).path("token").asString(); // 从登录响应读取本次测试内存中的明文 Token。

            HttpResponse<String> allowed = get(protectedUri(context), token); // 携带管理员 Bearer Token 请求测试专用管理端点。

            assertEquals(200, allowed.statusCode()); // 有效管理员 Token 必须通过认证和管理员角色授权。
            assertTrue(mapper.readTree(allowed.body()).path("ok").asBoolean()); // 测试端点返回 ok=true，证明 Controller 确实被执行。

            HttpResponse<String> missing = get(protectedUri(context)); // 不携带任何 Authorization 请求头访问同一管理端点。

            assertEquals(401, missing.statusCode()); // 缺少管理员 Token 必须被安全层拒绝为 401。

            JsonNode error = mapper.readTree(missing.body()); // 结构化读取安全层返回的统一错误体。
            assertEquals("UNAUTHORIZED", error.path("code").asString()); // 错误代码必须符合 P0-02 契约。
            assertEquals( // 错误体和响应头必须使用同一个 requestId。
                    missing.headers().firstValue("X-Request-Id").orElse(""), // 读取响应头中的 requestId。
                    error.path("requestId").asString() // 读取错误 JSON 中的 requestId。
            ); // requestId 一致性检查结束。
        } // 关闭应用上下文。
    } // 管理员 Token 权限边界测试结束。

     @Test // 执行真实 HTTP 注销验证。
    void logoutRevokesCurrentTokenAndSubsequentProtectedRequestIsUnauthorized() throws Exception { // 验证注销后原 Token 不能继续访问受保护接口。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用，并在测试结束后关闭上下文。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 使用应用实际配置的 JSON 映射器。

            HttpResponse<String> login = post( // 先通过正式登录接口取得管理员 Token。
                    loginUri(context), // 使用当前应用的随机端口。
                    "{\"username\":\"boundary-admin\",\"password\":\"boundary-password\"}" // 使用测试配置中的管理员凭据。
            ); // 登录请求结束。

            assertEquals(200, login.statusCode()); // 正确凭据必须登录成功。

            String token = mapper.readTree(login.body()).path("token").asString(); // 读取登录响应中的明文 Token。

            HttpResponse<String> beforeLogout = get(protectedUri(context), token); // 注销前携带 Token 访问受保护端点。
            assertEquals(200, beforeLogout.statusCode()); // 注销前 Token 必须仍然有效。

            HttpResponse<String> logout = postWithBearer(logoutUri(context), token); // 使用同一个 Token 调用正式注销接口。

            assertEquals(204, logout.statusCode()); // 注销成功必须返回 HTTP 204。
            assertTrue(logout.body().isEmpty()); // 204 响应不得返回响应体。

            HttpResponse<String> afterLogout = get(protectedUri(context), token); // 注销后再次使用原 Token 访问受保护端点。

            assertEquals(401, afterLogout.statusCode()); // 已撤销 Token 必须被识别为未认证。
            JsonNode error = mapper.readTree(afterLogout.body()); // 结构化读取统一错误响应体。
            assertEquals("UNAUTHORIZED", error.path("code").asString()); // 错误代码必须符合 P0-02 契约。
            assertEquals( // 错误体和响应头必须使用同一个 requestId。
                    afterLogout.headers().firstValue("X-Request-Id").orElse(""), // 读取响应头中的 requestId。
                    error.path("requestId").asString() // 读取错误 JSON 中的 requestId。
            ); // requestId 一致性检查结束。
        } // 关闭应用上下文。
    } // 注销后 Token 失效测试结束。

    @Test // 验证错误格式、未知 Token 和业务 Key 不能访问管理接口。
    void invalidCredentialFormatsCannotAccessAdminEndpoint() throws Exception { // 覆盖管理员凭据格式边界。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用，并在测试结束后关闭上下文。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 使用应用实际配置的 JSON 映射器。

            HttpResponse<String> unknownToken = get( // 发送格式正确但数据库中不存在的 Bearer Token。
                    protectedUri(context), // 请求测试专用的管理员受保护端点。
                    "A".repeat(43) // 生成符合长度格式但从未签发的测试 Token。
            ); // 未知 Token 请求结束。

            assertUnauthorized(mapper, unknownToken); // 未知 Token 必须返回统一 401 错误体。

            HttpResponse<String> wrongScheme = getWithHeader( // 发送错误认证方案，不能被当作管理员 Token。
                    protectedUri(context), // 请求同一个管理员受保护端点。
                    "Authorization", // 使用管理员认证头名称。
                    "Basic test-only-placeholder" // 使用 Basic 方案验证 Bearer 格式不会被放宽。
            ); // 错误认证方案请求结束。

            assertUnauthorized(mapper, wrongScheme); // 错误认证方案必须返回统一 401 错误体。

            HttpResponse<String> businessKeyOnly = getWithHeader( // 只发送业务 Key 请求头，不能冒充管理员 Token。
                    protectedUri(context), // 请求管理员受保护端点。
                    "X-HookFlow-Api-Key", // 使用 P0-02 规定的业务 Key 请求头名称。
                    "test-only-placeholder-not-a-real-key" // 仅使用测试占位文本，不写入真实密钥。
            ); // 业务 Key 请求结束。

            assertUnauthorized(mapper, businessKeyOnly); // 未实现业务 Key 校验时不能建立管理员身份，必须返回 401。
        } // 关闭应用上下文。
    } // 无效凭据边界测试结束。

    @Test // 验证数据库中已过期的管理员 Token 不能继续访问管理接口。
    void expiredAdminTokenCannotAccessProtectedEndpoint() throws Exception { // 覆盖请求过滤器对过期会话的校验。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用，并在测试结束后关闭上下文。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 使用应用实际配置的 JSON 映射器。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 使用当前应用连接的真实测试数据库。

            HttpResponse<String> login = post( // 先通过正式登录接口签发一个有效 Token。
                    loginUri(context), // 使用当前应用的随机端口。
                    "{\"username\":\"boundary-admin\",\"password\":\"boundary-password\"}" // 使用测试配置中的管理员凭据。
            ); // 登录请求结束。

            assertEquals(200, login.statusCode()); // 正确凭据必须登录成功。

            String token = mapper.readTree(login.body()).path("token").asString(); // 读取登录响应中的明文 Token。

            int updatedRows = jdbc.update( // 直接修改测试数据库中的到期时间，模拟 Token 已过期。
                    "UPDATE admin_session SET expires_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE token_hash = UNHEX(SHA2(?, 256))", // 按 Token 的 SHA-256 哈希定位会话，并设置为过去时间。
                    token // 仅在测试 SQL 参数中短暂使用明文 Token，不写入任何文件或日志。
            ); // 数据库更新结束。

            assertEquals(1, updatedRows); // 必须恰好修改本次登录创建的那条会话记录。

            HttpResponse<String> afterExpiry = get(protectedUri(context), token); // 使用已过期 Token 请求管理员受保护端点。

            assertUnauthorized(mapper, afterExpiry); // 过期 Token 必须返回统一 401 错误体。
        } // 关闭应用上下文。
    } // 过期 Token 权限测试结束。

    private ConfigurableApplicationContext start() { // 使用测试容器启动完整应用。
    byte[] keyBytes = new byte[32]; // AES-256 主密钥需要 32 字节。
    new SecureRandom().nextBytes(keyBytes); // 每次启动都生成新的测试密钥，不在仓库固定密钥值。
    String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 转为可传入启动参数的 Base64 文本。
    return new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动真实数据库配置的后端。
            "--spring.profiles.active=p2-login-test", // 启用现有数据库测试配置。
            "--server.port=0", // 使用随机空闲端口。
            "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本测试的 MySQL 容器。
            "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器提供的用户名。
            "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器提供的密码。
            "--HOOKFLOW_ADMIN_USERNAME=boundary-admin", // 设置测试管理员用户名。
            "--HOOKFLOW_ADMIN_PASSWORD=boundary-password", // 设置测试管理员密码。
            "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey // 仅在本次测试进程中提供随机主密钥。
    ); // 应用启动完成。
} // 测试启动方法结束。

    private URI loginUri(ConfigurableApplicationContext context) { // 构造登录接口地址。
        String port = context.getEnvironment().getRequiredProperty("local.server.port"); // 读取当前应用实际端口。
        return URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"); // 返回本机登录接口地址。
    } // 登录地址方法结束。

    private URI protectedUri(ConfigurableApplicationContext context) { // 构造测试专用管理接口地址。
        String port = context.getEnvironment().getRequiredProperty("local.server.port"); // 读取当前应用实际端口。
        return URI.create("http://127.0.0.1:" + port + "/api/v1/admin/test-protected"); // 返回测试专用受保护端点地址。
    } // 受保护端点地址方法结束。

    private URI logoutUri(ConfigurableApplicationContext context) { // 构造正式注销接口地址。
        String port = context.getEnvironment().getRequiredProperty("local.server.port"); // 读取当前应用实际端口。
        return URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"); // 返回管理员注销接口地址。
    } // 注销地址方法结束。

    private HttpResponse<String> postWithBearer(URI uri, String token) throws Exception { // 发送携带管理员 Token 的无请求体 POST 请求。
        HttpRequest request = HttpRequest.newBuilder(uri) // 开始构造 HTTP 请求。
                .header("Authorization", "Bearer " + token) // 按 P0-02 契约携带管理员 Bearer Token。
                .POST(HttpRequest.BodyPublishers.noBody()) // 注销接口不需要请求体。
                .build(); // 完成 HTTP 请求构造。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取响应。
    } // 带 Token 的 POST 请求方法结束。

    private HttpResponse<String> post(URI uri, String json) throws Exception { // 发送 JSON POST 请求。
        HttpRequest request = HttpRequest.newBuilder(uri) // 开始构造 HTTP 请求。
                .header("Content-Type", "application/json") // 声明请求体是 JSON。
                .POST(HttpRequest.BodyPublishers.ofString(json)) // 写入登录 JSON 请求体。
                .build(); // 完成 HTTP 请求构造。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取文本响应。
    } // POST 请求方法结束。

    private HttpResponse<String> get(URI uri, String token) throws Exception { // 发送带管理员 Token 的 GET 请求。
        HttpRequest request = HttpRequest.newBuilder(uri) // 开始构造 HTTP 请求。
                .header("Authorization", "Bearer " + token) // 按 P0-02 契约携带管理员 Bearer Token。
                .GET() // 使用 GET 方法访问测试端点。
                .build(); // 完成 HTTP 请求构造。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取文本响应。
    } // 带 Token 的 GET 请求方法结束。

    private HttpResponse<String> get(URI uri) throws Exception { // 发送不带 Authorization 的 GET 请求。
        HttpRequest request = HttpRequest.newBuilder(uri) // 开始构造 HTTP 请求。
                .GET() // 使用 GET 方法访问测试端点。
                .build(); // 完成 HTTP 请求构造。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取文本响应。
    } // 无 Token 的 GET 请求方法结束。

    private HttpResponse<String> getWithHeader(URI uri, String headerName, String headerValue) throws Exception { // 发送带指定请求头的 GET 请求。
        HttpRequest request = HttpRequest.newBuilder(uri) // 开始构造 HTTP 请求。
                .header(headerName, headerValue) // 写入本次测试需要验证的请求头。
                .GET() // 使用 GET 方法访问受保护端点。
                .build(); // 完成 HTTP 请求构造。
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取文本响应。
    } // 指定请求头的 GET 方法结束。

    private void assertUnauthorized(JsonMapper mapper, HttpResponse<String> response) throws Exception { // 统一验证 401 错误体和 requestId。
        assertEquals(401, response.statusCode()); // 无效凭据必须返回 HTTP 401。
        JsonNode error = mapper.readTree(response.body()); // 结构化读取统一错误响应体。
        assertEquals("UNAUTHORIZED", error.path("code").asString()); // 错误代码必须符合 P0-02 契约。
        assertEquals( // 错误体和响应头必须使用同一个 requestId。
                response.headers().firstValue("X-Request-Id").orElse(""), // 读取响应头中的 requestId。
                error.path("requestId").asString() // 读取错误 JSON 中的 requestId。
        ); // requestId 一致性检查结束。
    } // 统一 401 断言方法结束。
} // 管理员权限边界集成测试结束。