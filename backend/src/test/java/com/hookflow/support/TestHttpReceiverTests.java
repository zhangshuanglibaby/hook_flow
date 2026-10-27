package com.hookflow.support; // 测试与 HTTP 接收端放在同一包中。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较响应和收到的请求。
import static org.junit.jupiter.api.Assertions.assertNotNull; // 确认等待期间确实收到请求。
import java.io.IOException; // 声明本机 HTTP 通信可能失败。
import java.net.http.HttpClient; // 使用 Java 自带的真实 HTTP 客户端。
import java.net.http.HttpRequest; // 构造发往测试接收端的请求。
import java.net.http.HttpResponse; // 读取接收端实际返回的响应。
import java.time.Duration; // 为请求和接收等待设置上限。
import org.junit.jupiter.api.Test; // 标记 JUnit 测试方法。

class TestHttpReceiverTests { // 验证测试接收端自身可以正常工作。
    @Test // 检查默认响应及 GET 请求记录。
    void receivesGetAndReturnsDefaultResponse() throws IOException, InterruptedException { // 网络失败时让测试失败。
        try (TestHttpReceiver receiver = TestHttpReceiver.start()) { // 启动后在测试结束时关闭端口。
            HttpRequest request = HttpRequest.newBuilder(receiver.uri()) // 请求随机端口上的接收路径。
                    .timeout(Duration.ofSeconds(5)) // 防止网络请求无限等待。
                    .GET() // 使用 GET 方法。
                    .build(); // 完成请求构造。
            HttpResponse<String> response = HttpClient.newHttpClient() // 创建真实 HTTP 客户端。
                    .send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取响应。
            assertEquals(200, response.statusCode()); // 默认返回 HTTP 200。
            assertEquals("ok", response.body()); // 默认正文应为 ok。

            TestHttpReceiver.ReceivedRequest received = receiver.awaitRequest(Duration.ofSeconds(5)); // 等待接收记录。
            assertNotNull(received); // 超时未收到请求时立即失败。
            assertEquals("GET", received.method()); // 核对实际收到的方法。
            assertEquals("/receive", received.uri().getPath()); // 核对实际收到的路径。
        } // 自动关闭 HTTP 接收端。
    } // 默认响应测试结束。

    @Test // 检查 POST 正文、请求头和自定义响应。
    void receivesPostAndReturnsConfiguredResponse() throws IOException, InterruptedException { // 网络失败时让测试失败。
        try (TestHttpReceiver receiver = TestHttpReceiver.start()) { // 为本测试单独启动接收端。
            receiver.respondWith(503, "retry later"); // 指定测试用的服务不可用响应。
            HttpRequest request = HttpRequest.newBuilder(receiver.uri()) // 请求本机接收地址。
                    .timeout(Duration.ofSeconds(5)) // 限制请求等待时间。
                    .header("X-Test-Tag", "p1-05") // 添加用于断言的测试请求头。
                    .POST(HttpRequest.BodyPublishers.ofString("{\"eventId\":\"example\"}")) // 发送测试 JSON 文本。
                    .build(); // 完成请求构造。
            HttpResponse<String> response = HttpClient.newHttpClient() // 创建真实 HTTP 客户端。
                    .send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求并读取响应。
            assertEquals(503, response.statusCode()); // 返回预设的 HTTP 状态。
            assertEquals("retry later", response.body()); // 返回预设的响应正文。

            TestHttpReceiver.ReceivedRequest received = receiver.awaitRequest(Duration.ofSeconds(5)); // 取得收到的请求。
            assertNotNull(received); // 超时未收到请求时立即失败。
            assertEquals("POST", received.method()); // 核对请求方法。
            assertEquals("p1-05", received.headers().getFirst("X-Test-Tag")); // 核对请求头。
            assertEquals("{\"eventId\":\"example\"}", received.body()); // 核对收到的正文。
        } // 自动关闭 HTTP 接收端。
    } // 自定义响应测试结束。
} // 接收端测试类结束。