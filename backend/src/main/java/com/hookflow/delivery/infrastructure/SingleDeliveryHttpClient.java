package com.hookflow.delivery.infrastructure; // 单次 HTTP 请求属于投递基础设施层。

import java.io.IOException; // 将网络错误交给后续结果处理阶段。
import java.time.Clock; // 使用项目统一时钟生成每次请求的时间戳。
import java.util.Objects; // 在发送前检查必需参数。
import org.apache.hc.client5.http.classic.methods.HttpPost; // 构造 POST 请求。
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver; // 解析直连目标的默认端口。
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient; // 管理同步 HTTP 客户端。
import org.apache.hc.client5.http.impl.classic.HttpClients; // 创建客户端构建器。
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner; // 使用不主动选择代理的直连路由。
import org.apache.hc.core5.http.ClassicHttpResponse; // 获取响应状态并关闭响应。
import org.apache.hc.core5.http.ContentType; // 声明 JSON 请求体类型。
import org.apache.hc.core5.http.io.entity.ByteArrayEntity; // 原样发送规范化 JSON 字节。
import org.springframework.stereotype.Component; // 将客户端注册为 Spring 组件。

@Component // 由 Spring 管理客户端及其关闭动作。
public final class SingleDeliveryHttpClient implements AutoCloseable { // 只负责一次同步 HTTP 请求。
    private final Clock clock; // 保留统一时钟，供每次调用重新取时间。
    private final DeliveryRequestSigner signer; // 复用已验证的签名算法。
    private final CloseableHttpClient httpClient; // 复用禁止自动重试和重定向的客户端。

    public SingleDeliveryHttpClient(Clock clock, DeliveryRequestSigner signer) { // 注入时钟和签名器。
        this.clock = Objects.requireNonNull(clock, "时钟缺失"); // 不允许使用缺失的时钟。
        this.signer = Objects.requireNonNull(signer, "签名器缺失"); // 不允许发送无签名请求。
        this.httpClient = HttpClients.custom() // 创建同步 HTTP 客户端。
                .disableAutomaticRetries() // 网络失败或响应异常时不自动再次发送。
                .disableRedirectHandling() // 收到 3xx 时不访问 Location 指向的新地址。
                .disableContentCompression() // 不自动解压响应内容。
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)) // 采用直连路由，不启用系统代理。
                .build(); // 完成客户端构造；此处尚未发送请求。
    } // 构造方法结束。

    public int postOnce(String targetUrl, String eventId, byte[] canonicalJsonUtf8, // 接收目标、事件 ID 和规范化 JSON 字节。
            String signingSecret) throws IOException { // 明文密钥仅在本次调用中使用，网络错误原样上抛。
        Objects.requireNonNull(targetUrl, "投递地址缺失"); // 拒绝缺失的目标地址。
        Objects.requireNonNull(canonicalJsonUtf8, "请求体缺失"); // 拒绝缺失的请求体。
        byte[] body = canonicalJsonUtf8.clone(); // 固定本次请求的字节，供签名和发送共同使用。
        String timestamp = clock.instant().toString(); // 每次调用重新生成 UTC 时间戳。
        String signature = signer.sign(eventId, timestamp, body, signingSecret); // 在发送前计算本次签名。
        HttpPost request = new HttpPost(targetUrl); // 只构造一条 POST 请求。
        request.setEntity(new ByteArrayEntity(body, ContentType.create("application/json"))); // 原样附上同一份 JSON 字节。
        request.setHeader("X-HookFlow-Event-Id", eventId); // 让接收端识别事件。
        request.setHeader("X-HookFlow-Timestamp", timestamp); // 让接收端取得参与签名的时间戳。
        request.setHeader("X-HookFlow-Signature", signature); // 发送小写十六进制 HMAC 签名。
        try (ClassicHttpResponse response = httpClient.executeOpen(null, request, null)) { // 发出一次请求并确保关闭响应。
            return response.getCode(); // 仅返回状态码；分类和响应摘要留给后续任务。
        } // 响应资源关闭结束。
    } // 单次投递方法结束。

    @Override // 实现组件关闭时的资源释放。
    public void close() throws IOException { // 释放客户端持有的连接资源。
        httpClient.close(); // 关闭 HTTP 客户端。
    } // 关闭方法结束。
} // 单次投递客户端结束。