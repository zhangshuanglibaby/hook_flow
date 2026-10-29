package com.hookflow.support; // 将接收端限定在测试源码中。

import com.sun.net.httpserver.Headers; // 保存收到的 HTTP 请求头。
import com.sun.net.httpserver.HttpExchange; // 读取请求并写回响应。
import com.sun.net.httpserver.HttpServer; // 启动轻量级本机 HTTP 服务。
import java.io.IOException; // 声明网络操作可能失败。
import java.net.InetSocketAddress; // 指定监听地址和随机端口。
import java.net.URI; // 表示接收地址及请求路径。
import java.nio.charset.StandardCharsets; // 固定请求和响应使用 UTF-8。
import java.time.Duration; // 指定等待请求的最长时间。
import java.util.List; // 复制请求头的多个值。
import java.util.Objects; // 拒绝空的响应内容。
import java.util.concurrent.BlockingQueue; // 在线程间传递收到的请求。
import java.util.concurrent.LinkedBlockingQueue; // 保存测试尚未检查的请求。
import java.util.concurrent.TimeUnit; // 将等待时间换算为毫秒。

public final class TestHttpReceiver implements AutoCloseable { // 可在测试结束时关闭的本机接收端。
    private final HttpServer server; // 保存实际监听的 HTTP 服务。
    private final BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>(); // 保存收到的请求。
    private volatile Response response = new Response(200, "ok", null); // 默认返回成功响应。

    private TestHttpReceiver() throws IOException { // 只允许通过 start 方法启动。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // 只监听本机，端口由系统分配。
        server.createContext("/receive", this::receive); // 将测试请求交给接收方法。
        server.start(); // 开始接受真实 HTTP 连接。
    } // 构造方法结束。

    public static TestHttpReceiver start() throws IOException { // 创建并启动一个独立接收端。
        return new TestHttpReceiver(); // 将启动后的实例交给测试。
    } // 启动方法结束。

    public URI uri() { // 提供测试客户端要访问的地址。
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/receive"); // 使用实际分配的端口。
    } // 地址方法结束。

    public void respondWith(int status, String body) { // 配置后续请求收到的状态和正文。
        if (status < 200 || status > 599) { // 限定为可直接返回的最终响应状态。
            throw new IllegalArgumentException("HTTP status must be between 200 and 599"); // 拒绝无效配置。
        } // 状态检查结束。
        response = new Response(status, Objects.requireNonNull(body), null); // 一次替换整组响应配置。
    } // 配置响应的方法结束。

    public void respondWithRedirect(URI location) { // 为重定向禁用测试配置真实 Location。
        response = new Response(302, "redirect", Objects.requireNonNull(location).toString()); // 保存明确的跳转目标。
    } // 重定向响应配置结束。

    public ReceivedRequest awaitRequest(Duration timeout) throws InterruptedException { // 等待接收端收到请求。
        return requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS); // 超时返回 null，避免测试无限等待。
    } // 等待方法结束。

    private void receive(HttpExchange exchange) throws IOException { // 处理一次真实 HTTP 请求。
        try { // 确保处理结束后释放连接。
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); // 读取完整请求正文。
            Headers headers = new Headers(); // 创建独立的请求头副本。
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values))); // 复制每个请求头。
            requests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), headers, body)); // 交给测试断言。
            Response configured = response; // 对这次请求固定一组响应配置。
            byte[] bytes = configured.body().getBytes(StandardCharsets.UTF_8); // 按 UTF-8 编码响应正文。
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // 告知客户端正文编码。
            if (configured.location() != null) { // 只有重定向测试会指定目标。
                exchange.getResponseHeaders().set("Location", configured.location()); // 让客户端确实有可跟随的地址。
            } // Location 配置结束。
            exchange.sendResponseHeaders(configured.status(), bytes.length == 0 ? -1 : bytes.length); // 发送状态和正文长度。
            if (bytes.length > 0) { // 有正文时才写入响应流。
                exchange.getResponseBody().write(bytes); // 将配置的内容发给客户端。
            } // 正文写入结束。
        } finally { // 无论请求是否处理成功都执行清理。
            exchange.close(); // 关闭请求和响应流，释放连接。
        } // 清理结束。
    } // 请求处理方法结束。

    @Override // 实现 AutoCloseable，方便测试使用 try-with-resources。
    public void close() { // 停止本次测试创建的服务。
        server.stop(0); // 关闭监听端口，避免测试之间互相影响。
    } // 关闭方法结束。

    public record ReceivedRequest(String method, URI uri, Headers headers, String body) {} // 保存收到的方法、路径、请求头和正文。
    private record Response(int status, String body, String location) {} // 保存响应状态、正文和可选跳转地址。
} // HTTP 测试接收端结束。
