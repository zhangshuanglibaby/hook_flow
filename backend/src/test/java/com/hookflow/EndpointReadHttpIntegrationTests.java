package com.hookflow; // 测试类位于应用根包，以便启动完整后端。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较 HTTP 状态和 JSON 字段。
import static org.junit.jupiter.api.Assertions.assertTrue; // 确认列表字段确实是数组。
import java.net.URI; // 构造测试服务器上的接口地址。
import java.net.http.HttpClient; // 发送真实 HTTP 请求。
import java.net.http.HttpRequest; // 构造登录和查询请求。
import java.net.http.HttpResponse; // 读取真实 HTTP 响应。
import java.security.SecureRandom; // 为测试启动生成临时主密钥配置值。
import java.util.Base64; // 将临时随机字节转换成配置字符串。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动完整应用。
import org.springframework.context.ConfigurableApplicationContext; // 在测试结束时关闭应用。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实 MySQL 中是否产生了成功编辑审计。
import org.testcontainers.junit.jupiter.Container; // 管理临时数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用容器测试扩展。
import org.testcontainers.mysql.MySQLContainer; // 使用真实 MySQL 8.4.13。
import tools.jackson.databind.JsonNode; // 结构化读取接口响应。
import tools.jackson.databind.json.JsonMapper; // 使用应用配置的 JSON 映射器。

@Testcontainers // 测试期间启动并清理临时 MySQL。
class EndpointReadHttpIntegrationTests { // 验证 P3-01 已实现的只读接口。
    @Container // 将数据库容器交给 JUnit 管理。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 使用现有测试约定的版本。

    @Test // 通过真实 HTTP 验证空数据库中的列表、鉴权和参数错误。
    void readsEmptyListAndRejectsInvalidRequests() throws Exception { // 请求或断言失败时直接使测试失败。
        byte[] keyBytes = new byte[32]; // 准备仅用于本次测试启动的随机配置值。
        new SecureRandom().nextBytes(keyBytes); // 运行时生成随机值，不写入代码或数据库。
        String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 转换为环境配置可接受的字符串。
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动正式应用并确保最终关闭。
                "--spring.profiles.active=p2-login-test", // 使用已有的真实数据库测试配置，而非 skeleton。
                "--server.port=0", // 选择随机空闲端口。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本测试独有的临时 MySQL。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器提供的数据库用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器提供的数据库密码。
                "--HOOKFLOW_ADMIN_USERNAME=endpoint-read-admin", // 设置测试管理员用户名。
                "--HOOKFLOW_ADMIN_PASSWORD=endpoint-read-password", // 设置测试管理员密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey)) { // 仅满足当前启动配置；本测试不创建接收端或密文。
            HttpClient client = HttpClient.newHttpClient(); // 创建真实网络请求客户端。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 取得应用实际使用的 JSON 映射器。
            String base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"); // 取得随机端口。

            HttpResponse<String> missing = get(client, base + "/api/v1/admin/endpoints", null); // 不带管理员 Token 查询。
            assertEquals(401, missing.statusCode()); // 管理接口必须拒绝匿名请求。

            HttpRequest loginRequest = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login")) // 构造正式登录请求。
                    .header("Content-Type", "application/json") // 声明 JSON 请求体。
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"endpoint-read-admin\",\"password\":\"endpoint-read-password\"}")) // 提交测试账户。
                    .build(); // 完成登录请求。
            HttpResponse<String> login = client.send(loginRequest, HttpResponse.BodyHandlers.ofString()); // 发送真实登录请求。
            assertEquals(200, login.statusCode()); // 测试管理员应登录成功。
            String token = mapper.readTree(login.body()).path("token").asString(); // 仅在测试内存中保存管理员 Token。

            HttpResponse<String> list = get(client, base + "/api/v1/admin/endpoints", token); // 查询尚无接收端的真实数据库。
            assertEquals(200, list.statusCode()); // 空列表应成功返回，而不是 404。
            JsonNode body = mapper.readTree(list.body()); // 解析分页响应。
            assertEquals(1, body.path("page").asInt()); // 默认查询第 1 页。
            assertEquals(20, body.path("size").asInt()); // 默认每页 20 条。
            assertEquals(0, body.path("total").asLong()); // 新数据库没有接收端。
            assertTrue(body.path("items").isArray()); // items 必须是数组。
            assertEquals(0, body.path("items").size()); // 空列表没有条目。

            HttpResponse<String> badPage = get(client, base + "/api/v1/admin/endpoints?page=0", token); // 提交非法页码。
            assertEquals(400, badPage.statusCode()); // 页码必须从 1 开始。
            assertEquals("INVALID_REQUEST", mapper.readTree(badPage.body()).path("code").asString()); // 使用统一错误代码。

            HttpResponse<String> badSize = get(client, base + "/api/v1/admin/endpoints?size=101", token); // 超过每页上限。
            assertEquals(400, badSize.statusCode()); // 每页最多 100 条。

            HttpResponse<String> badId = get(client, base + "/api/v1/admin/endpoints/0", token); // 使用不合法的路径 ID。
            assertEquals(400, badId.statusCode()); // ID 必须是正整数。

            HttpResponse<String> missingId = get(client, base + "/api/v1/admin/endpoints/1", token); // 查询不存在的正整数 ID。
            assertEquals(404, missingId.statusCode()); // 不存在的接收端返回 404。
            assertEquals("NOT_FOUND", mapper.readTree(missingId.body()).path("code").asString()); // 使用统一错误代码。
            HttpResponse<String> anonymousPatch = patch(client, base + "/api/v1/admin/endpoints/1", null, "{\"name\":\"updated\"}"); // 未登录时尝试编辑。
            assertEquals(401, anonymousPatch.statusCode()); // 编辑接口必须要求管理员身份。

            HttpResponse<String> emptyPatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{}"); // 提交不含可编辑字段的对象。
            assertEquals(400, emptyPatch.statusCode()); // 空编辑必须被拒绝。

            HttpResponse<String> urlPatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{\"url\":\"https://example.com\"}"); // 尝试修改尚未实现安全校验的 URL。
            assertEquals(400, urlPatch.statusCode()); // 当前阶段不能保存未经验证的外部地址。

            HttpResponse<String> typePatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{\"targetType\":\"SIMULATOR\"}"); // 尝试改变创建后不可变的类型。
            assertEquals(400, typePatch.statusCode()); // 类型不属于 PATCH 允许的字段。

            HttpResponse<String> nullNamePatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{\"name\":null}"); // 显式提交空名称。
            assertEquals(400, nullNamePatch.statusCode()); // 显式 null 不能当成“保持原值”。

            HttpResponse<String> badTimeoutPatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{\"timeoutMs\":15001}"); // 提交超过契约上限的超时。
            assertEquals(400, badTimeoutPatch.statusCode()); // 越界值必须在进入编辑事务前被拒绝。

            HttpResponse<String> missingPatch = patch(client, base + "/api/v1/admin/endpoints/1", token, "{\"name\":\"updated\"}"); // 对不存在的接收端提交合法名称。
            assertEquals(404, missingPatch.statusCode()); // 不存在时返回统一 404。
            assertEquals("NOT_FOUND", mapper.readTree(missingPatch.body()).path("code").asString()); // 错误体使用既有代码。

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 从实际运行的应用取得数据库查询组件。
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, "ENDPOINT_UPDATED")); // 所有失败请求都不得产生成功编辑审计。
        } // 关闭应用；容器由测试扩展清理。
    } // 只读接口测试结束。

    private static HttpResponse<String> get(HttpClient client, String address, String token) throws Exception { // 发送指定地址的 GET 请求。
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(address)); // 构造目标地址。
        if (token != null) { // 仅在已登录场景添加管理员凭据。
            builder.header("Authorization", "Bearer " + token); // 使用契约规定的认证头。
        } // 请求头处理结束。
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()); // 发送请求并读取响应文本。
    } // GET 测试辅助方法结束。

    private static HttpResponse<String> patch(HttpClient client, String address, String token, String jsonBody) throws Exception { // 通过真实网络发送 PATCH 请求。
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(address)) // 构造指定接收端地址的 HTTP 请求。
                .header("Content-Type", "application/json"); // 告诉服务器请求体采用 JSON 格式。
        if (token != null) { // 已登录场景才携带管理员凭据。
            builder.header("Authorization", "Bearer " + token); // 使用现有管理员认证方式。
        } // 凭据设置结束。
        HttpRequest request = builder.method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody)).build(); // 使用给定 JSON 构造 PATCH 请求体。
        return client.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并返回实际 HTTP 响应。
    } // PATCH 测试辅助方法结束。
} // 测试类结束。