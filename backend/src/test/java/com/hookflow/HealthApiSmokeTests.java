package com.hookflow; // 将冒烟测试放在应用根包下。

import static org.junit.jupiter.api.Assertions.assertEquals; // 检查 HTTP 状态和响应 JSON。
import static org.junit.jupiter.api.Assertions.assertFalse; // 检查请求关联 ID 不为空。
import java.io.IOException; // 声明 HTTP 传输可能产生的异常。
import java.net.URI; // 构造仅指向本机测试服务的地址。
import java.net.http.HttpClient; // 使用 Java 21 自带的真实 HTTP 客户端。
import java.net.http.HttpRequest; // 构造健康检查请求。
import java.net.http.HttpResponse; // 读取服务端实际返回的响应。
import org.junit.jupiter.api.Test; // 标记 JUnit 测试方法。
import org.springframework.beans.factory.annotation.Autowired; // 注入测试上下文中的环境。
import org.springframework.boot.test.context.SpringBootTest; // 启动完整的 Spring Boot 测试应用。
import org.springframework.core.env.Environment; // 读取随机分配的服务端口。
import org.springframework.test.context.ActiveProfiles; // 指定这项测试使用的配置组。

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) // 在随机端口启动真实 HTTP 服务。
@ActiveProfiles("skeleton") // 此 API 冒烟测试不连接数据库，也不充当迁移验证。
class HealthApiSmokeTests { // 验证现有健康接口可通过网络访问。
    @Autowired // 从已启动的测试应用取得环境对象。
    private Environment environment; // 保存随机端口所在的配置来源。

    @Test // 让 JUnit 执行 HTTP 冒烟检查。
    void healthEndpointRespondsOverHttp() throws IOException, InterruptedException { // 网络或线程中断时直接让测试失败。
        String port = environment.getRequiredProperty("local.server.port"); // 读取本次启动时实际分配的端口。
        URI uri = URI.create("http://127.0.0.1:" + port + "/actuator/health"); // 只访问本机的健康检查路径。
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build(); // 构造不带凭据的 GET 请求。
        HttpResponse<String> response = HttpClient.newHttpClient() // 创建 JDK 自带的 HTTP 客户端。
                .send(request, HttpResponse.BodyHandlers.ofString()); // 真正发送请求并读取响应文本。

        assertEquals(200, response.statusCode()); // 健康接口必须公开且返回 HTTP 200。
        assertEquals("{\"status\":\"UP\"}", response.body()); // 只暴露最小健康状态。
        assertFalse(response.headers().firstValue("X-Request-Id").orElse("").isBlank()); // 正式过滤器应生成非空关联 ID。
    } // 测试方法结束。
} // 冒烟测试类结束。