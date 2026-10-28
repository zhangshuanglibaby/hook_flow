package com.hookflow; // 从应用根包启动完整后端，验证真实 HTTP 创建。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较状态、字段和数据库记录。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对其他响应不泄露一次性密钥。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对密文能还原为本次交付的密钥。
import java.net.URI; // 构造随机端口上的真实 HTTP 地址。
import java.net.http.HttpClient; // 发送真实网络请求。
import java.net.http.HttpRequest; // 构造管理员请求。
import java.net.http.HttpResponse; // 检查响应状态、头和 JSON。
import java.security.SecureRandom; // 生成测试专用的环境主密钥。
import java.util.Base64; // 校验签名密钥字节数并编码环境主密钥。
import org.junit.jupiter.api.Test; // 声明集成测试用例。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动正式后端配置。
import org.springframework.context.ConfigurableApplicationContext; // 测试结束时关闭应用。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实 MySQL 中的密文与审计。
import org.testcontainers.junit.jupiter.Container; // 让扩展管理临时 MySQL。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用容器生命周期。
import org.testcontainers.mysql.MySQLContainer; // 使用项目既有的数据库版本。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 仅在测试内存中核对数据库密文。
import tools.jackson.databind.JsonNode; // 结构化读取正式 JSON 响应。
import tools.jackson.databind.json.JsonMapper; // 使用应用实际的 JSON 映射器。

@Testcontainers // 为本类创建独立的临时 MySQL 数据库。
class EndpointCreateHttpIntegrationTests { // 区分真实 HTTP 创建与仅调用应用服务的测试。
    @Container // 交由 JUnit 启停容器。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 与既有集成测试保持一致。

    @Test // 覆盖管理员创建、一次性交付及所有后续查询/编辑不泄密。
    void createsEndpointsAndDeliversExternalSecretOnlyOnce() throws Exception { // 真实请求失败时让测试直接失败。
        try (ConfigurableApplicationContext context = start()) { // 使用真实 Flyway V1 表启动完整应用。
            HttpClient client = HttpClient.newHttpClient(); // 建立请求客户端。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 解析应用生成的实际响应。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 检查数据库值与同事务审计。
            EndpointSigningSecretCipher cipher = context.getBean(EndpointSigningSecretCipher.class); // 只用于测试时验证密文。
            String base = base(context); // 从应用随机端口构造地址。
            String token = login(client, mapper, base); // 通过正式登录接口取得管理员 Token。
            String address = base + "/api/v1/admin/endpoints"; // 管理员创建和列表共用路径。

            HttpResponse<String> anonymous = post(client, address, null, "{\"name\":\"anonymous\",\"targetType\":\"SIMULATOR\"}"); // 不携带管理员 Token。
            assertEquals(401, anonymous.statusCode()); // 匿名创建不得进入控制器。
            HttpResponse<String> simulator = post(client, address, token, "{\"name\":\"simulator-post\",\"targetType\":\"SIMULATOR\",\"enabled\":false}"); // 不提供 URL 创建模拟器。
            assertEquals(201, simulator.statusCode()); // 新建成功应返回 201。
            JsonNode simulatorBody = mapper.readTree(simulator.body()); // 结构化读取模拟器创建响应。
            long simulatorId = simulatorBody.path("id").asLong(); // 保存主键供数据库核对。
            assertEquals("SIMULATOR", simulatorBody.path("targetType").asString()); // 类型必须是内部模拟器。
            assertTrue(simulatorBody.path("url").isNull()); // 模拟器只能使用内部目标，URL 为 null。
            assertEquals(5000, simulatorBody.path("timeoutMs").asInt()); // 缺省超时按契约取 5000。
            assertFalse(simulatorBody.path("enabled").asBoolean()); // 显式 false 不能被默认 true 覆盖。
            assertFalse(simulatorBody.has("signingSecret")); // 模拟器响应绝不交付明文密钥。
            assertEquals(0, jdbc.queryForObject("SELECT enabled FROM endpoint WHERE id = ?", Integer.class, simulatorId)); // 数据库确实保持停用状态。
            assertEquals(null, jdbc.queryForObject("SELECT url FROM endpoint WHERE id = ?", String.class, simulatorId)); // 数据库模拟器 URL 仍为 NULL。

            HttpResponse<String> external = post(client, address, token, // 通过真正的管理员 HTTP 创建外部接收端。
                    "{\"name\":\"external-post\",\"targetType\":\"EXTERNAL\",\"url\":\"https://hooks.acme.com/hook\",\"enabled\":false}"); // 使用允许保存的公开 HTTPS 域名。
            assertEquals(201, external.statusCode()); // 成功时才有一次性创建响应。
            assertEquals("no-store", external.headers().firstValue("Cache-Control").orElse("")); // 含密钥的响应不得缓存。
            JsonNode externalBody = mapper.readTree(external.body()); // 解析实际序列化后的 JSON。
            long externalId = externalBody.path("id").asLong(); // 取得真实新建主键。
            String secret = externalBody.path("signingSecret").asString(); // 仅在测试内存中保存此次交付的明文。
            assertEquals(32, Base64.getUrlDecoder().decode(secret).length); // 签名密钥对应独立的 32 字节随机值。
            assertEquals("EXTERNAL", externalBody.path("targetType").asString()); // 响应类型必须与数据库一致。
            assertEquals("https://hooks.acme.com/hook", externalBody.path("url").asString()); // 响应 URL 为已保存的安全目标。
            assertFalse(externalBody.path("enabled").asBoolean()); // 创建时指定的停用状态应保留。
            assertFalse(externalBody.has("signingSecretCiphertext")); // 创建响应也不得包含数据库密文。
            byte[] stored = jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", // 仅在测试中读取实际密文。
                    (rs, rowNum) -> rs.getBytes(1), externalId); // 按 HTTP 返回的真实主键定位。
            assertTrue(secret.equals(cipher.decryptForDelivery(stored))); // 数据库密文必须能还原为此次交付的密钥。
            String requestId = external.headers().firstValue("X-Request-Id").orElse(""); // 获取服务端产生的请求关联 ID。
            Long actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "endpoint-create-admin"); // 取得实际认证管理员 ID。
            assertEquals(1L, jdbc.queryForObject( // 创建成功应恰好写入同事务成功审计。
                    "SELECT COUNT(*) FROM audit_log WHERE request_id = ? AND action = 'ENDPOINT_CREATED' AND object_type = 'ENDPOINT' AND object_id = ? AND actor_user_id = ?",
                    Long.class, requestId, Long.toString(externalId), actor)); // 同时核对关联 ID、对象及操作者。

            String detailAddress = address + "/" + externalId; // 后续接口只通过安全响应视图读取。
            HttpResponse<String> detail = get(client, detailAddress, token); // 再次查询外部接收端详情。
            assertEquals(200, detail.statusCode()); // 已创建的记录必须可读取。
            assertFalse(mapper.readTree(detail.body()).has("signingSecret")); // 后续 GET 不得再交付密钥字段。
            assertFalse(detail.body().contains(secret)); // GET 文本中也不能包含实际明文。
            HttpResponse<String> edited = patch(client, detailAddress, token, "{\"name\":\"external-renamed\"}"); // 执行现有安全 PATCH。
            assertEquals(200, edited.statusCode()); // 名称编辑应成功。
            assertFalse(mapper.readTree(edited.body()).has("signingSecret")); // PATCH 响应不得包含一次性密钥字段。
            assertFalse(edited.body().contains(secret)); // PATCH 文本中也不得包含实际明文。
            HttpResponse<String> list = get(client, address, token); // 查询接收端列表。
            assertEquals(200, list.statusCode()); // 分页列表必须正常返回。
            assertFalse(list.body().contains("signingSecret")); // 列表条目和分页容器都不能有密钥字段。
            assertFalse(list.body().contains(secret)); // 列表文本也不能包含明文。
            assertTrue(secret.equals(cipher.decryptForDelivery(jdbc.queryForObject( // PATCH 不能轮换已创建的签名密钥。
                    "SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", (rs, rowNum) -> rs.getBytes(1), externalId)))); // 再次读取原密文。

            HttpResponse<String> second = post(client, address, token, // 再创建另一个外部接收端。
                    "{\"name\":\"external-second\",\"targetType\":\"EXTERNAL\",\"url\":\"https://hooks.acme.com/second\",\"timeoutMs\":7000}"); // 不提供 enabled，使用默认值。
            assertEquals(201, second.statusCode()); // 第二次独立创建成功。
            JsonNode secondBody = mapper.readTree(second.body()); // 读取第二次一次性交付的密钥。
            assertTrue(secondBody.path("enabled").asBoolean()); // 可选启用状态默认 true。
            assertEquals(7000, secondBody.path("timeoutMs").asInt()); // 显式超时应保存。
            assertFalse(secret.equals(secondBody.path("signingSecret").asString())); // 每条 EXTERNAL 的密钥必须独立。
        } // 关闭真实应用和数据库连接。
    } // 成功创建及一次性交付测试结束。

    @Test // 验证创建请求字段和危险 URL 在写库前被拒绝。
    void rejectsInvalidCreationWithoutEndpointOrSuccessAudit() throws Exception { // 使用真实 HTTP 与 MySQL 核对失败副作用。
        try (ConfigurableApplicationContext context = start()) { // 启动完整后端并确保关闭。
            HttpClient client = HttpClient.newHttpClient(); // 创建真实请求客户端。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 解析统一错误体。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 统计失败前后的数据库行。
            String address = base(context) + "/api/v1/admin/endpoints"; // 管理员创建路径。
            String token = login(client, mapper, base(context)); // 取得有效管理员凭据。
            Long beforeEndpoints = jdbc.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class); // 记录接收端基线。
            Long beforeAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ENDPOINT_CREATED'", Long.class); // 记录创建审计基线。
            HttpRequest rotate = HttpRequest.newBuilder(URI.create(base(context) + "/api/v1/admin/api-key/rotate")) // 构造正式业务 Key 轮换请求。
                    .header("Authorization", "Bearer " + token) // 只允许管理员取得新业务 Key。
                    .POST(HttpRequest.BodyPublishers.noBody()).build(); // 轮换接口不需要请求体。
            HttpResponse<String> rotation = client.send(rotate, HttpResponse.BodyHandlers.ofString()); // 真实请求取得已验证的业务身份凭据。
            assertEquals(200, rotation.statusCode()); // 确认业务 Key 不是无效占位字符串。
            String businessKey = mapper.readTree(rotation.body()).path("apiKey").asString(); // 密钥仅保留在测试内存。
            HttpRequest businessCreate = HttpRequest.newBuilder(URI.create(address)) // 用已签发业务 Key 请求新增管理员 POST。
                    .header("X-HookFlow-Api-Key", businessKey) // 不携带管理员 Bearer Token。
                    .header("Content-Type", "application/json") // 提交本来可创建的合法 JSON。
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"business-denied\",\"targetType\":\"SIMULATOR\"}")).build(); // 完成跨权限请求。
            HttpResponse<String> forbidden = client.send(businessCreate, HttpResponse.BodyHandlers.ofString()); // 经过正式鉴权过滤链。
            assertEquals(403, forbidden.statusCode()); // 已认证的业务身份不能创建管理员接收端。
            assertEquals("FORBIDDEN", mapper.readTree(forbidden.body()).path("code").asString()); // 保持统一的权限错误契约。
            assertEquals(forbidden.headers().firstValue("X-Request-Id").orElse(""), // 请求 ID 应由服务端生成。
                    mapper.readTree(forbidden.body()).path("requestId").asString()); // 错误体与响应头必须一致。
            String[] badRequests = { // 定义字段错误或 URL 语法错误的请求。
                    "{}", // 缺少两个必填字段。
                    "{\"name\":\"bad\",\"targetType\":\"OTHER\"}", // 未知目标类型。
                    "{\"name\":\"bad\",\"targetType\":\"SIMULATOR\",\"url\":null}", // 模拟器即使显式 null 也不接受 URL 字段。
                    "{\"name\":\"bad\",\"targetType\":\"SIMULATOR\",\"url\":\"https://hooks.acme.com/hook\"}", // 模拟器不得借 URL 绕过安全检查。
                    "{\"name\":\"bad\",\"targetType\":\"SIMULATOR\",\"enabled\":\"false\"}", // 启用状态必须是 JSON 布尔值。
                    "{\"name\":\"bad\",\"targetType\":\"SIMULATOR\",\"timeoutMs\":15001}", // 超时不能超过数据库范围。
                    "{\"name\":\"bad\",\"targetType\":\"SIMULATOR\",\"signingSecret\":\"fake\"}", // 不允许调用方指定密钥。
                    "{\"name\":\"bad\",\"targetType\":\"EXTERNAL\"}", // 外部目标 URL 必填。
                    "{\"name\":\"bad\",\"targetType\":\"EXTERNAL\",\"url\":\"https://\"}" // 缺少主机是格式错误。
            }; // 400 请求列表结束。
            for (String json : badRequests) { // 逐个验证无副作用的字段拒绝。
                HttpResponse<String> rejected = post(client, address, token, json); // 发出管理员真实 HTTP 请求。
                assertEquals(400, rejected.statusCode(), json); // 类型或格式错误返回 400。
                assertEquals("INVALID_REQUEST", mapper.readTree(rejected.body()).path("code").asString()); // 使用既有统一错误代码。
                assertEquals(rejected.headers().firstValue("X-Request-Id").orElse(""), // 错误体的请求 ID 必须来自服务端。
                        mapper.readTree(rejected.body()).path("requestId").asString()); // 核对头与响应体一致。
            } // 字段错误检查结束。
            String[] prohibitedUrls = { // 可解析但策略禁止保存的外部目标。
                    "http://hooks.acme.com/hook", // 明文 HTTP 不允许。
                    "https://127.0.0.1/hook", // 回环 IPv4 字面地址不允许。
                    "https://192.168.1.8/hook", // 私网 IPv4 字面地址不允许。
                    "https://169.254.169.254/latest/meta-data", // 链路本地字面地址不允许。
                    "https://[::1]/hook", // 回环 IPv6 字面地址不允许。
                    "https://localhost/hook", // 本地主机不允许。
                    "https://receiver.internal/hook", // 内部用途域名后缀不允许。
                    "https://user:pass@hooks.acme.com/hook", // URL 凭据不允许。
                    "https://hooks.acme.com/hook?token=demo" // 当前保守策略拒绝所有查询串。
            }; // 422 目标列表结束。
            for (String url : prohibitedUrls) { // 验证保存时 URL 安全规则实际接入创建路径。
                String json = mapper.writeValueAsString(java.util.Map.of( // 用结构化映射器生成有效 JSON。
                        "name", "bad-external", "targetType", "EXTERNAL", "url", url)); // 提交待拒绝的目标。
                HttpResponse<String> rejected = post(client, address, token, json); // 发出真实管理员创建请求。
                assertEquals(422, rejected.statusCode(), url); // 安全策略拒绝而非格式错误。
                assertEquals("TARGET_NOT_ALLOWED", mapper.readTree(rejected.body()).path("code").asString()); // 保持稳定错误代码。
                assertFalse(rejected.body().contains(url)); // 错误响应不能回显可能含凭据的 URL。
            } // 禁止目标检查结束。
            assertEquals(beforeEndpoints, jdbc.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class)); // 失败请求没有写入任何接收端。
            assertEquals(beforeAudits, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ENDPOINT_CREATED'", Long.class)); // 失败请求没有成功创建审计。
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ?", // 被业务 Key 拒绝的请求不得有任何成功审计。
                    Long.class, forbidden.headers().firstValue("X-Request-Id").orElseThrow())); // 使用服务端请求 ID 定位。
        } // 关闭应用上下文。
    } // 创建拒绝与零副作用测试结束。

    private static ConfigurableApplicationContext start() { // 使用随机主密钥和临时数据库启动正式配置。
        byte[] keyBytes = new byte[32]; // 准备符合生产校验的主密钥长度。
        new SecureRandom().nextBytes(keyBytes); // 每次启动生成新的测试专用主密钥。
        String startupKey = Base64.getEncoder().encodeToString(keyBytes); // 转为规范标准 Base64。
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run( // 启动真实 HTTP 服务和 Flyway。
                "--spring.profiles.active=p2-login-test", // 沿用现有真实数据库测试配置。
                "--server.port=0", // 使用随机空闲端口。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 接入本类独立的 MySQL 容器。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器提供的用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器提供的密码。
                "--HOOKFLOW_ADMIN_USERNAME=endpoint-create-admin", // 配置测试管理员。
                "--HOOKFLOW_ADMIN_PASSWORD=endpoint-create-password", // 配置测试专用密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=" + startupKey); // 密钥只存在于本次测试进程。
    } // 测试应用启动方法结束。

    private static String base(ConfigurableApplicationContext context) { // 读取应用实际选择的 HTTP 端口。
        return "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"); // 返回本机测试服务地址。
    } // 基础地址构造结束。

    private static String login(HttpClient client, JsonMapper mapper, String base) throws Exception { // 经正式管理员登录取得 Token。
        HttpResponse<String> response = post(client, base + "/api/v1/auth/login", null, // 登录接口不携带已有 Token。
                "{\"username\":\"endpoint-create-admin\",\"password\":\"endpoint-create-password\"}"); // 提交测试专用管理员凭据。
        assertEquals(200, response.statusCode()); // 登录成功是后续授权测试的前提。
        return mapper.readTree(response.body()).path("token").asString(); // Token 只留在测试内存中。
    } // 管理员登录辅助方法结束。

    private static HttpResponse<String> post(HttpClient client, String address, String token, String json) throws Exception { // 发送真实 JSON POST。
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(address)).header("Content-Type", "application/json"); // 声明请求类型。
        if (token != null) { // 管理员请求才携带有效 Bearer Token。
            request.header("Authorization", "Bearer " + token); // 沿用正式安全过滤链。
        } // 请求凭据设置结束。
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()); // 返回实际响应文本。
    } // POST 请求辅助方法结束。

    private static HttpResponse<String> get(HttpClient client, String address, String token) throws Exception { // 查询已创建接收端。
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)).header("Authorization", "Bearer " + token).GET().build(); // 构造管理员 GET。
        return client.send(request, HttpResponse.BodyHandlers.ofString()); // 发送并读取真实响应。
    } // GET 请求辅助方法结束。

    private static HttpResponse<String> patch(HttpClient client, String address, String token, String json) throws Exception { // 编辑已创建接收端。
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)) // 指向现有管理员详情路径。
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json") // 携带管理员身份和 JSON 类型。
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(); // 发送限定的 PATCH 请求体。
        return client.send(request, HttpResponse.BodyHandlers.ofString()); // 返回实际编辑响应。
    } // PATCH 请求辅助方法结束。
} // 真实 HTTP 创建集成测试类结束。
