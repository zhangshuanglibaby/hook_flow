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

import static org.junit.jupiter.api.Assertions.assertFalse; // 核对真实 HTTP 响应不泄露签名密钥。
import java.util.UUID; // 为内部安全创建和成功编辑生成审计请求 ID。
import com.hookflow.endpoint.application.EndpointSecretProvisioningService; // 在测试内安全创建模拟器接收端。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 取得测试接收端的内部 ID。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 仅在测试内存中取得实际签名密钥供泄露检查。

@Testcontainers // 测试期间启动并清理临时 MySQL。
class EndpointReadHttpIntegrationTests { // 通过真实 HTTP 验证接收端查询、有限编辑和密钥不泄露。
    @Container // 将数据库容器交给 JUnit 管理。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 使用现有测试约定的版本。

    @Test // 先验证空库及失败请求，再验证已创建接收端的查询和成功编辑。
    void readsEndpointsAndRejectsInvalidRequestsWithoutLeakingSecrets() throws Exception { // 请求或断言失败时直接使测试失败。
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
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey)) { // 使用本次测试专用的随机主密钥加密内部创建的接收端。
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
            EndpointSecretProvisioningService provisioning = context.getBean(EndpointSecretProvisioningService.class); // 取得内部安全创建服务，不开放 HTTP 创建。
            EndpointSigningSecretCipher cipher = context.getBean(EndpointSigningSecretCipher.class); // 取得本次启动的加密组件。
            Long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "endpoint-read-admin"); // 查询真实管理员 ID。
            EndpointConfiguration created = provisioning.createSimulator("http-visible-simulator", 5000, actor, UUID.randomUUID().toString()); // 用独立随机密钥创建内部模拟器。
            byte[] stored = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", (rs, rowNum) -> rs.getBytes(1), created.id()); // 仅测试代码读取数据库原始密文。
            String secret = cipher.decryptForDelivery(stored); // 仅测试代码在内存中取得明文，用于核对 HTTP 不泄露。
            String detailAddress = base + "/api/v1/admin/endpoints/" + created.id(); // 构造已存在接收端的真实 HTTP 地址。

            HttpResponse<String> populatedList = get(client, base + "/api/v1/admin/endpoints", token); // 查询非空接收端列表。
            assertEquals(200, populatedList.statusCode()); // 非空列表应正常返回。
            JsonNode listed = mapper.readTree(populatedList.body()); // 解析真实列表响应。
            assertEquals(1, listed.path("items").size()); // 本测试只创建了一条接收端。
            assertEquals(created.id(), listed.path("items").get(0).path("id").asLong()); // 列表应包含刚创建的接收端。
            assertFalse(listed.path("items").get(0).has("signingSecret")); // 列表条目不得有明文密钥字段。
            assertFalse(listed.path("items").get(0).has("signingSecretCiphertext")); // 列表条目也不得有密文字段。
            assertFalse(populatedList.body().contains(secret)); // 列表响应文本不得包含实际明文密钥。

            HttpResponse<String> populatedDetail = get(client, detailAddress, token); // 查询已存在接收端的详情。
            assertEquals(200, populatedDetail.statusCode()); // 详情应正常返回。
            JsonNode detailed = mapper.readTree(populatedDetail.body()); // 解析真实详情响应。
            assertEquals(created.id(), detailed.path("id").asLong()); // 详情应对应目标接收端。
            assertFalse(detailed.has("signingSecret")); // 后续详情查询绝不返还创建时的密钥。
            assertFalse(detailed.has("signingSecretCiphertext")); // 详情也不暴露数据库密文。
            assertFalse(populatedDetail.body().contains(secret)); // 详情响应文本不得包含实际明文。

            HttpResponse<String> edited = patch(client, detailAddress, token, "{\"name\":\"edited-simulator\",\"timeoutMs\":7000}"); // 通过现有限定 PATCH 进行成功编辑。
            assertEquals(200, edited.statusCode()); // 已支持的名称和超时编辑应成功。
            JsonNode editedBody = mapper.readTree(edited.body()); // 解析编辑后的真实响应。
            assertEquals("edited-simulator", editedBody.path("name").asString()); // 响应应包含新名称。
            assertEquals(7000, editedBody.path("timeoutMs").asInt()); // 响应应包含新超时。
            assertFalse(editedBody.has("signingSecret")); // 编辑响应不得返回签名密钥。
            assertFalse(edited.body().contains(secret)); // 编辑响应文本不得泄露实际明文。
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ENDPOINT_UPDATED' AND object_id = ?", Long.class, Long.toString(created.id()))); // 成功 HTTP 编辑应记录一次审计。
        } // 关闭应用；容器由测试扩展清理。
    } // 真实 HTTP 测试结束：已覆盖查询、有限编辑和密钥不泄露。

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
} // 接收端真实 HTTP 测试类结束。