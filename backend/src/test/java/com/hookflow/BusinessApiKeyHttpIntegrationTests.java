package com.hookflow; // 放在应用根包，以便启动完整后端。

import static org.junit.jupiter.api.Assertions.assertArrayEquals; // 核对数据库保存的 Key 摘要。
import static org.junit.jupiter.api.Assertions.assertEquals; // 核对 HTTP 状态、响应字段和记录数。
import static org.junit.jupiter.api.Assertions.assertFalse; // 确认后一次响应不再包含旧 Key。
import static org.junit.jupiter.api.Assertions.assertTrue; // 核对有效身份和错误字段。

import java.net.URI; // 构造本机测试服务器的请求地址。
import java.net.http.HttpClient; // 发送真实 HTTP 请求。
import java.net.http.HttpRequest; // 构造 POST 请求及认证请求头。
import java.net.http.HttpResponse; // 读取真实 HTTP 响应。
import java.nio.charset.StandardCharsets; // 按 ASCII 计算 Key 的摘要。
import java.security.MessageDigest; // 独立计算预期的 SHA-256 摘要。

import java.util.concurrent.CountDownLatch; // 让两个轮换请求从同一个起点开始。
import java.util.concurrent.ExecutorService; // 管理并发请求的工作线程。
import java.util.concurrent.Executors; // 创建固定数量的工作线程。
import java.util.concurrent.Future; // 等待每个 HTTP 请求的结果。
import java.util.concurrent.TimeUnit; // 为等待设置明确超时。

import java.time.Clock; // 读取应用实际注入的 UTC 时钟。
import java.time.LocalDateTime; // 比较数据库审计时间与请求执行时间。
import java.time.ZoneOffset; // 将时钟读数明确转换为 UTC。
import java.time.temporal.ChronoUnit; // 与数据库 DATETIME(3) 的毫秒精度对齐。
import java.util.Map; // 接收审计行的字段名和值。

import org.junit.jupiter.api.Test; // 标记集成测试方法。
import org.springframework.boot.builder.SpringApplicationBuilder; // 启动完整应用。
import org.springframework.context.ConfigurableApplicationContext; // 测试结束时关闭应用。
import org.springframework.jdbc.core.JdbcTemplate; // 查询真实 MySQL 中的 Key 记录。
import org.testcontainers.junit.jupiter.Container; // 交给 JUnit 管理数据库容器。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers 扩展。
import org.testcontainers.mysql.MySQLContainer; // 使用真实 MySQL 8.4.13。

import tools.jackson.databind.JsonNode; // 结构化读取 JSON 响应。
import tools.jackson.databind.json.JsonMapper; // 使用应用实际的 JSON 映射器。

import static org.junit.jupiter.api.Assertions.assertThrows; // 验证审计插入失败会向调用方抛出异常。
import java.util.UUID; // 为直接调用应用服务生成测试请求 ID。
import org.springframework.dao.DataIntegrityViolationException; // 识别审计操作者外键约束失败。
import com.hookflow.auth.application.BusinessApiKeyService; // 通过真实 Spring 事务代理调用轮换服务。

@Testcontainers // 测试运行时启动临时 MySQL，并在结束后清理。
class BusinessApiKeyHttpIntegrationTests { // 验证 P2-03 的实际 HTTP 和数据库行为。

    @Container // 声明本测试类的临时数据库容器。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 与现有集成测试使用相同的 MySQL 版本。

    @Test // 验证顺序轮换及两类凭据的权限边界。
    void rotationRevokesOldKeyAndKeepsBusinessIdentitySeparate() throws Exception { // HTTP、JSON 或数据库操作异常时直接使测试失败。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用，并在测试结束时关闭。
            HttpClient client = HttpClient.newHttpClient(); // 创建本次测试使用的 HTTP 客户端。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 取得应用实际配置的 JSON 映射器。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 连接当前临时 MySQL 数据库。

            HttpResponse<String> noAdmin = post(client, uri(context, "/api/v1/admin/api-key/rotate"), null, null, null); // 无管理员 Token 尝试轮换。
            assertSecurityError(mapper, noAdmin, 401, "UNAUTHORIZED"); // 未认证请求不得签发业务 Key。
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ?", Long.class, noAdmin.headers().firstValue("X-Request-Id").orElseThrow())); // 未登录的轮换请求不能产生成功审计。

            HttpResponse<String> login = post(client, uri(context, "/api/v1/auth/login"), null, null,
                    "{\"username\":\"key-test-admin\",\"password\":\"key-test-password\"}"); // 使用测试专用账户登录。
            assertEquals(200, login.statusCode()); // 正确的测试账户应登录成功。
            String adminToken = mapper.readTree(login.body()).path("token").asString(); // 仅在测试内存中保存管理员 Token。
            
            LocalDateTime beforeFirstRotation = LocalDateTime.ofInstant(context.getBean(Clock.class).instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); // 记录首次请求开始前的 UTC 毫秒时间。
            
            HttpResponse<String> first = post(client, uri(context, "/api/v1/admin/api-key/rotate"), adminToken, null, null); // 管理员首次轮换业务 Key。
            
            LocalDateTime afterFirstRotation = LocalDateTime.ofInstant(context.getBean(Clock.class).instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); // 记录首次响应返回后的 UTC 毫秒时间。

            assertEquals(200, first.statusCode()); // 首次轮换应成功。
            JsonNode firstBody = mapper.readTree(first.body()); // 结构化读取本次轮换响应。
            assertEquals(1, firstBody.size()); // 成功响应只能包含 apiKey 一个字段。
            String firstKey = firstBody.path("apiKey").asString(); // 仅在测试内存中保存首次签发的 Key。
            assertEquals(43, firstKey.length()); // 32 字节随机值的无填充 URL 安全编码为 43 字符。

            String firstRequestId = first.headers().firstValue("X-Request-Id").orElse(""); // 从成功响应读取服务端生成的请求 ID。
            assertFalse(firstRequestId.isBlank()); // 成功响应必须带有可关联的请求 ID。
            Map<String, Object> firstAudit = jdbc.queryForMap("SELECT actor_user_id, action, object_type, object_id, occurred_at, request_id FROM audit_log WHERE request_id = ?", firstRequestId); // 按本次请求 ID 查询真实 MySQL 中唯一的审计行。
            Long expectedActorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "key-test-admin"); // 从数据库取得实际登录管理员的内部 ID。
            assertEquals(expectedActorId.longValue(), ((Number) firstAudit.get("actor_user_id")).longValue()); // 审计操作者必须是本次登录的管理员。
            assertEquals("API_KEY_ROTATED", firstAudit.get("action")); // 审计操作必须是业务 Key 轮换。
            assertEquals("BUSINESS_API_KEY", firstAudit.get("object_type")); // 被操作对象采用约定的业务 Key 类型。
            assertEquals("1", firstAudit.get("object_id")); // 对象 ID 为约定的单一业务 Key 逻辑槽位。
            assertEquals(firstRequestId, firstAudit.get("request_id")); // 数据库请求 ID 必须与 HTTP 响应头一致。
            LocalDateTime auditTime = jdbc.queryForObject("SELECT occurred_at FROM audit_log WHERE request_id = ?", (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), firstRequestId); // 以数据库时间字段读取审计发生时刻。
            LocalDateTime keyCreatedAt = jdbc.queryForObject("SELECT created_at FROM api_key WHERE revoked_at IS NULL", (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime()); // 读取首次轮换创建的新 Key 的 UTC 数据库时间。
            assertEquals(keyCreatedAt, auditTime); // Key 创建与成功审计必须复用同一事务取得的毫秒时间。
            assertFalse(auditTime.isBefore(beforeFirstRotation)); // 审计时间不得早于请求开始前。
            assertFalse(auditTime.isAfter(afterFirstRotation)); // 审计时间不得晚于响应返回后。
            String auditText = firstAudit.toString(); // 汇集本条审计行的字段值，仅用于测试敏感值检查。
            assertFalse(auditText.contains(firstKey)); // 审计行不得保存明文业务 Key。
            assertFalse(auditText.contains(adminToken)); // 审计行不得保存管理员 Token。
            assertFalse(auditText.contains("key-test-password")); // 审计行不得保存测试账户密码。
            assertFalse(auditText.contains("test-only-placeholder-not-a-real-key")); // 审计行不得保存测试配置中的加密密钥占位值。

            byte[] storedHash = jdbc.queryForObject(
                    "SELECT key_hash FROM api_key WHERE revoked_at IS NULL",
                    (rs, rowNum) -> rs.getBytes(1)); // 读取唯一有效记录的二进制摘要。
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(firstKey.getBytes(StandardCharsets.US_ASCII)), storedHash); // 数据库只保存明文 Key 的 SHA-256 摘要。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM api_key WHERE revoked_at IS NULL", Long.class)); // 首次轮换后恰有一条有效记录。

            HttpResponse<String> allowed = post(client, uri(context, "/api/v1/events"), null, firstKey, null); // 有效业务 Key 请求仅存在于测试中的事件入口。
            assertEquals(200, allowed.statusCode()); // 有效 Key 应通过业务身份授权。
            assertTrue(mapper.readTree(allowed.body()).path("ok").asBoolean()); // 测试 Controller 确实执行，但没有保存事件。

            HttpResponse<String> missing = post(client, uri(context, "/api/v1/events"), null, null, null); // 不携带任何业务凭据请求事件入口。
            assertSecurityError(mapper, missing, 401, "UNAUTHORIZED"); // 缺少业务 Key 必须返回统一 401。

            HttpResponse<String> unknown = post(client, uri(context, "/api/v1/events"), null, "A".repeat(43), null); // 使用从未签发的测试占位 Key。
            assertSecurityError(mapper, unknown, 401, "UNAUTHORIZED"); // 未知 Key 不能建立业务身份。

            HttpResponse<String> businessInAdmin = post(client, uri(context, "/api/v1/admin/api-key/rotate"), null, firstKey, null); // 用已验证的真实业务身份访问管理员接口。
            assertSecurityError(mapper, businessInAdmin, 403, "FORBIDDEN"); // 已认证的业务身份不能冒充管理员。
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ?", Long.class, businessInAdmin.headers().firstValue("X-Request-Id").orElseThrow())); // 业务身份被管理员接口拒绝后不得产生成功审计。   
            
            HttpResponse<String> adminInBusiness = post(client, uri(context, "/api/v1/events"), adminToken, null, null); // 用有效管理员 Token 访问业务入口。
            assertSecurityError(mapper, adminInBusiness, 403, "FORBIDDEN"); // 已认证的管理员身份不能冒充业务 Key。

            HttpResponse<String> bothHeaders = post(client, uri(context, "/api/v1/events"), adminToken, firstKey, null); // 同时提交两类凭据。
            assertSecurityError(mapper, bothHeaders, 403, "FORBIDDEN"); // Authorization 存在时不能再借业务 Key 获得业务身份。

            HttpResponse<String> second = post(client, uri(context, "/api/v1/admin/api-key/rotate"), adminToken, null, null); // 同一管理员再次轮换。
            assertEquals(200, second.statusCode()); // 第二次轮换应成功提交。
            JsonNode secondBody = mapper.readTree(second.body()); // 读取第二次成功响应。
            assertEquals(1, secondBody.size()); // 第二次响应也只能包含本次的新 Key。
            String secondKey = secondBody.path("apiKey").asString(); // 将新 Key 暂存于测试内存。
            assertTrue(!firstKey.equals(secondKey)); // 两次轮换不能产生相同的明文 Key。
            assertFalse(second.body().contains(firstKey)); // 后一次响应不能再次披露旧 Key。

            HttpResponse<String> revoked = post(client, uri(context, "/api/v1/events"), null, firstKey, null); // 第二次提交后立即重试旧 Key。
            assertSecurityError(mapper, revoked, 401, "UNAUTHORIZED"); // 已撤销 Key 必须立即失效。

            HttpResponse<String> newest = post(client, uri(context, "/api/v1/events"), null, secondKey, null); // 使用第二次轮换的新 Key。
            assertEquals(200, newest.statusCode()); // 新 Key 必须可以通过业务身份校验。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM api_key WHERE revoked_at IS NULL", Long.class)); // 数据库仍然只能有一条有效 Key。
            assertEquals(Long.valueOf(2), jdbc.queryForObject( // 只统计本测试两次轮换创建的记录，不受测试执行顺序影响。
        "SELECT COUNT(*) FROM api_key WHERE key_hash IN (UNHEX(SHA2(?, 256)), UNHEX(SHA2(?, 256)))", // 用两个 Key 的摘要定位本测试记录。
        Long.class, firstKey, secondKey)); // 两条记录都保留，旧 Key 只是被撤销。
        } // 关闭 HTTP 服务和应用上下文。
    } // 顺序轮换与权限边界测试结束。

     @Test // 验证两个管理员请求同时轮换时的数据库结果。
    void concurrentRotationsLeaveAtMostOneActiveKey() throws Exception { // 并发或 HTTP 操作异常时使测试失败。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用并连接临时 MySQL。
            HttpClient client = HttpClient.newHttpClient(); // 两个工作线程共用同一个 HTTP 客户端。
            JsonMapper mapper = context.getBean(JsonMapper.class); // 读取应用实际的 JSON 响应。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 查询本测试使用的真实数据库。
            URI rotateUri = uri(context, "/api/v1/admin/api-key/rotate"); // 构造正式轮换接口地址。
            URI eventsUri = uri(context, "/api/v1/events"); // 构造仅存在于测试中的业务入口地址。

            HttpResponse<String> login = post(client, uri(context, "/api/v1/auth/login"), null, null,
                    "{\"username\":\"key-test-admin\",\"password\":\"key-test-password\"}"); // 使用测试账户取得管理员 Token。
            assertEquals(200, login.statusCode()); // 管理员登录必须成功。
            String adminToken = mapper.readTree(login.body()).path("token").asString(); // Token 只保存在测试内存。

            HttpResponse<String> badBody = post(client, uri(context, "/api/v1/admin/api-key/rotate"), adminToken, null, "{\"unexpected\":true}"); // 已登录管理员提交轮换接口不接受的非空请求体。
            assertSecurityError(mapper, badBody, 400, "INVALID_REQUEST"); // 请求应在进入轮换事务前被拒绝。
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ?", Long.class, badBody.headers().firstValue("X-Request-Id").orElseThrow())); // 参数错误不得伪造成功审计。

            HttpResponse<String> initial = post(client, rotateUri, adminToken, null, null); // 先创建一条有效 Key，供并发轮换撤销。
            assertEquals(200, initial.statusCode()); // 初始轮换必须成功。
            String initialRequestId = initial.headers().firstValue("X-Request-Id").orElseThrow(); // 取得并发开始前那次成功轮换的服务端请求 ID。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ? AND action = 'API_KEY_ROTATED'", Long.class, initialRequestId)); // 初始成功轮换必须对应恰好一条成功审计。
            String initialKey = mapper.readTree(initial.body()).path("apiKey").asString(); // 暂存旧 Key 以检查其失效。

            ExecutorService workers = Executors.newFixedThreadPool(2); // 创建两个同时发起请求的工作线程。
            CountDownLatch ready = new CountDownLatch(2); // 等待两个线程都准备好。
            CountDownLatch release = new CountDownLatch(1); // 同时放行两个轮换请求。
            HttpResponse<String> first; // 保存第一个轮换响应。
            HttpResponse<String> second; // 保存第二个轮换响应。
            try { // 无论断言或请求是否失败，都要释放并关闭工作线程。
                Future<HttpResponse<String>> firstFuture = workers.submit(() -> { // 提交第一个轮换任务。
                    ready.countDown(); // 告知主线程：第一个任务已准备好。
                    release.await(); // 等待主线程同时放行两个任务。
                    return post(client, rotateUri, adminToken, null, null); // 发起真正的管理员 HTTP 轮换请求。
                }); // 第一个任务提交结束。
                Future<HttpResponse<String>> secondFuture = workers.submit(() -> { // 提交第二个轮换任务。
                    ready.countDown(); // 告知主线程：第二个任务已准备好。
                    release.await(); // 等待同一个放行信号。
                    return post(client, rotateUri, adminToken, null, null); // 发起另一次真正的 HTTP 轮换请求。
                }); // 第二个任务提交结束。

                assertTrue(ready.await(10, TimeUnit.SECONDS)); // 两个线程都就绪后才开始并发请求。
                release.countDown(); // 同时放行两个轮换任务。
                first = firstFuture.get(60, TimeUnit.SECONDS); // 在限定时间内取得第一个响应。
                second = secondFuture.get(60, TimeUnit.SECONDS); // 在限定时间内取得第二个响应。
            } finally { // 即使等待超时，也执行线程清理。
                release.countDown(); // 避免未放行的线程一直等待。
                workers.shutdownNow(); // 结束本次测试创建的工作线程。
            } // 并发请求执行与清理结束。

            assertEquals(200, first.statusCode()); // 第一个管理员轮换请求必须成功。
            assertEquals(200, second.statusCode()); // 第二个管理员轮换请求也必须成功。
            String firstRotationRequestId = first.headers().firstValue("X-Request-Id").orElseThrow(); // 取得第一个成功并发请求的服务端请求 ID。
            String secondRotationRequestId = second.headers().firstValue("X-Request-Id").orElseThrow(); // 取得第二个成功并发请求的服务端请求 ID。
            assertFalse(firstRotationRequestId.equals(secondRotationRequestId)); // 两个独立请求不能共用同一个请求 ID。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ? AND action = 'API_KEY_ROTATED'", Long.class, firstRotationRequestId)); // 第一个成功轮换必须提交恰好一条审计。
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ? AND action = 'API_KEY_ROTATED'", Long.class, secondRotationRequestId)); // 第二个成功轮换也必须提交恰好一条审计。
            String firstKey = mapper.readTree(first.body()).path("apiKey").asString(); // 读取第一个成功响应中的 Key。
            String secondKey = mapper.readTree(second.body()).path("apiKey").asString(); // 读取第二个成功响应中的 Key。
            assertTrue(!firstKey.equals(secondKey)); // 两次成功轮换必须签发不同的 Key。

            assertEquals(Long.valueOf(1), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM api_key WHERE revoked_at IS NULL", Long.class)); // 两个事务结束后数据库最多且恰好一条有效 Key。
            assertSecurityError(mapper, post(client, eventsUri, null, initialKey, null), 401, "UNAUTHORIZED"); // 并发轮换前的旧 Key 已失效。

            HttpResponse<String> firstUse = post(client, eventsUri, null, firstKey, null); // 检查第一个并发响应的 Key。
            HttpResponse<String> secondUse = post(client, eventsUri, null, secondKey, null); // 检查第二个并发响应的 Key。
            int validCount = (firstUse.statusCode() == 200 ? 1 : 0)
                    + (secondUse.statusCode() == 200 ? 1 : 0); // 统计仍能通过业务鉴权的 Key 数量。
            assertEquals(1, validCount); // 两个新 Key 中只能有一个仍有效。
            if (firstUse.statusCode() == 200) { // 如果第一个 Key 是最终有效的 Key。
                assertSecurityError(mapper, secondUse, 401, "UNAUTHORIZED"); // 第二个 Key 必须已被后续轮换撤销。
            } else { // 否则第二个 Key 应为最终有效 Key。
                assertSecurityError(mapper, firstUse, 401, "UNAUTHORIZED"); // 第一个 Key 必须已被后续轮换撤销。
            } // 两个并发轮换结果的有效性检查结束。
        } // 关闭完整应用和测试 HTTP 服务。
    } // 并发轮换测试结束。

    @Test // 验证审计数据库写入失败时，整个轮换事务回滚。
    void auditInsertFailureRollsBackKeyRotation() { // 直接测试应用服务与真实 MySQL 的事务边界。
        try (ConfigurableApplicationContext context = start()) { // 启动完整应用，结束时关闭上下文。
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class); // 使用本次测试的真实 MySQL 连接。
            BusinessApiKeyService apiKeys = context.getBean(BusinessApiKeyService.class); // 获取带事务代理的轮换应用服务。
            Long actorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, "key-test-admin"); // 取得存在的管理员内部 ID。
            String oldKey = apiKeys.rotate(actorId.longValue(), UUID.randomUUID().toString()); // 先成功签发一条有效 Key，作为回滚前状态。
            Long keyCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM api_key", Long.class); // 记录失败操作前的 Key 总数。
            Long auditCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class); // 记录失败操作前的审计总数。
            String failedRequestId = UUID.randomUUID().toString(); // 为预期失败的调用准备独立请求 ID。
            assertThrows(DataIntegrityViolationException.class, () -> apiKeys.rotate(Long.MAX_VALUE, failedRequestId)); // 不存在的操作者触发审计外键失败。
            assertTrue(apiKeys.isActive(oldKey)); // 原有效 Key 的撤销必须随事务回滚。
            assertEquals(keyCountBefore, jdbc.queryForObject("SELECT COUNT(*) FROM api_key", Long.class)); // 失败调用插入的新 Key 不能留在数据库。
            assertEquals(auditCountBefore, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)); // 失败调用不能留下成功审计。
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id = ?", Long.class, failedRequestId)); // 失败请求 ID 不得关联审计行。
        } // 关闭测试应用上下文。
    } // 审计失败回滚测试结束。

    private ConfigurableApplicationContext start() { // 用测试专属数据库启动正式应用配置。
        return new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test", // 启用真实数据库及测试专用端点。
                "--server.port=0", // 自动选择空闲端口，避免与本机服务冲突。
                "--DB_URL=" + MYSQL.getJdbcUrl(), // 连接本测试类的临时 MySQL。
                "--DB_USERNAME=" + MYSQL.getUsername(), // 使用容器提供的用户名。
                "--DB_PASSWORD=" + MYSQL.getPassword(), // 使用容器提供的密码。
                "--HOOKFLOW_ADMIN_USERNAME=key-test-admin", // 配置本测试的初始管理员用户名。
                "--HOOKFLOW_ADMIN_PASSWORD=key-test-password", // 配置本测试的初始管理员密码。
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY=test-only-placeholder-not-a-real-key"); // 只使用测试占位值，不写入真实密钥。
    } // 应用启动方法结束。

    private URI uri(ConfigurableApplicationContext context, String path) { // 构造本次应用的 HTTP 地址。
        String port = context.getEnvironment().getRequiredProperty("local.server.port"); // 读取应用实际监听的随机端口。
        return URI.create("http://127.0.0.1:" + port + path); // 仅请求本机测试服务器。
    } // HTTP 地址构造结束。

    private HttpResponse<String> post(HttpClient client, URI uri, String adminToken, String apiKey, String json) throws Exception { // 发送指定凭据的真实 POST 请求。
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri); // 开始构造请求。
        if (adminToken != null) { // 仅在指定管理员 Token 时写入 Authorization。
            builder.header("Authorization", "Bearer " + adminToken); // 使用契约规定的 Bearer 方案。
        } // 管理员请求头处理结束。
        if (apiKey != null) { // 仅在指定业务 Key 时写入业务请求头。
            builder.header("X-HookFlow-Api-Key", apiKey); // 使用契约规定的业务 Key 请求头。
        } // 业务请求头处理结束。
        if (json != null) { // 仅登录请求需要 JSON 请求体。
            builder.header("Content-Type", "application/json"); // 声明请求体为 JSON。
        } // 请求体媒体类型处理结束。
        HttpRequest request = builder.POST(json == null
                ? HttpRequest.BodyPublishers.noBody() // 轮换和测试事件入口不发送请求体。
                : HttpRequest.BodyPublishers.ofString(json)).build(); // 登录时发送 JSON，并完成请求。
        return client.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取文本响应。
    } // POST 请求方法结束。

    private void assertSecurityError(JsonMapper mapper, HttpResponse<String> response, int status, String code) throws Exception { // 检查统一安全错误体。
        assertEquals(status, response.statusCode()); // 首先核对 HTTP 401 或 403。
        JsonNode body = mapper.readTree(response.body()); // 结构化解析错误 JSON。
        assertEquals(3, body.size()); // 错误体只能包含 code、message、requestId。
        assertEquals(code, body.path("code").asString()); // 核对契约规定的稳定错误代码。
        assertTrue(!body.path("message").asString().isBlank()); // 错误消息必须存在。
        String requestId = response.headers().firstValue("X-Request-Id").orElse(""); // 读取响应头中的请求 ID。
        assertTrue(!requestId.isBlank()); // 响应头不能缺少请求 ID。
        assertEquals(requestId, body.path("requestId").asString()); // 错误体和响应头的请求 ID 必须一致。
    } // 统一安全错误断言结束。
} // P2-03 HTTP 集成测试类结束。