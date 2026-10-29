package com.hookflow.delivery.infrastructure; // 单次 HTTP 请求属于投递基础设施层。

import java.io.IOException; // 将网络错误交给后续结果处理阶段。
import java.net.URI; // 结构化表示外部或固定内部目标。
import java.time.Clock; // 每次请求从统一时钟获取签名时间戳。
import java.util.Objects; // 拒绝缺失的必需参数。
import org.apache.hc.client5.http.DnsResolver; // 为连接管理器指定实际使用的解析器。
import org.apache.hc.client5.http.SystemDefaultDnsResolver; // 模拟器固定本机通道使用普通解析器。
import org.apache.hc.client5.http.classic.methods.HttpPost; // 构造单次 POST 请求。
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver; // 解析直连目标的默认端口。
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient; // 管理同步 HTTP 客户端。
import org.apache.hc.client5.http.impl.classic.HttpClients; // 创建客户端构建器。
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder; // 把解析器安装在实际连接管理器上。
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner; // 强制使用不选择代理的直连路由。
import org.apache.hc.core5.http.ClassicHttpResponse; // 获取状态并关闭响应。
import org.apache.hc.core5.http.ContentType; // 声明 JSON 请求体类型。
import org.apache.hc.core5.http.io.entity.ByteArrayEntity; // 原样发送规范化 JSON 字节。
import org.springframework.beans.factory.annotation.Autowired; // 指定 Spring 使用正式构造方法。
import org.springframework.stereotype.Component; // 将客户端注册为 Spring 组件。

@Component // 由 Spring 管理客户端及其关闭动作。
public final class SingleDeliveryHttpClient implements AutoCloseable { // 每次显式调用只发出一条请求。
    private final Clock clock; // 保存统一时钟。
    private final DeliveryRequestSigner signer; // 保存签名器。
    private final SimulatorInternalTargetRoute simulatorRoute; // 保存不接收用户 URL 的内部路由。
    private final CloseableHttpClient externalClient; // 外部目标专用、带连接时 DNS 防护的客户端。
    private final CloseableHttpClient simulatorClient; // 模拟器专用、与外部连接池隔离的客户端。

    @Autowired // 两个构造方法并存时明确选择正式依赖注入入口。
    public SingleDeliveryHttpClient(Clock clock, DeliveryRequestSigner signer,
            SimulatorInternalTargetRoute simulatorRoute) { // 接收正式环境的三个组件。
        this(clock, signer, simulatorRoute, new GuardedDnsResolver()); // 正式外部连接始终启用受控解析器。
    }

    SingleDeliveryHttpClient(Clock clock, DeliveryRequestSigner signer,
            SimulatorInternalTargetRoute simulatorRoute, GuardedDnsResolver guardedResolver) { // 仅供同包隔离测试注入可控 DNS。
        this.clock = Objects.requireNonNull(clock, "时钟缺失"); // 不允许缺失时间戳来源。
        this.signer = Objects.requireNonNull(signer, "签名器缺失"); // 不允许发送无签名请求。
        this.simulatorRoute = Objects.requireNonNull(simulatorRoute, "模拟器内部路由缺失"); // 内部目标不得由外部 URL 代替。
        this.externalClient = newClient(Objects.requireNonNull(guardedResolver, "受控 DNS 缺失")); // 外部连接池只能使用受控 DNS。
        this.simulatorClient = newClient(SystemDefaultDnsResolver.INSTANCE); // 独立连接池仅供固定本机模拟器路径使用。
    }

    private static CloseableHttpClient newClient(DnsResolver resolver) { // 为每一种目标建立独立连接池。
        return HttpClients.custom() // 创建同步 HTTP 客户端。
                .disableAutomaticRetries() // 禁止网络失败时自动重发。
                .disableRedirectHandling() // 禁止跟随可能指向内网的重定向。
                .disableContentCompression() // 禁止自动解压响应。
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)) // 不使用环境代理。
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create() // 创建实际建连使用的连接管理器。
                        .setDnsResolver(resolver) // 在连接管理器的 DNS 路径安装指定解析器。
                        .build()) // 完成这一目标类型专用的连接管理器。
                .build(); // 完成客户端构造，此时尚未发送请求。
    }

    public int postOnce(String targetUrl, String eventId, byte[] canonicalJsonUtf8,
            String signingSecret) throws IOException { // 外部目标每次显式调用发送一次。
        URI target = URI.create(Objects.requireNonNull(targetUrl, "投递地址缺失")); // 结构化读取目标。
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null
                || target.getRawUserInfo() != null || target.getRawQuery() != null
                || target.getRawFragment() != null) { // 外部通道只接受无凭据的 HTTPS 主机目标。
            throw new IllegalArgumentException("外部投递目标必须是合法 HTTPS 地址"); // 阻止把内部 HTTP 路径传入外部通道。
        }
        return sendOnce(externalClient, target, eventId, canonicalJsonUtf8, signingSecret); // 实际连接经过受控 DNS。
    }

    public int postSimulatorOnce(String eventId, byte[] canonicalJsonUtf8,
            String signingSecret) throws IOException { // 模拟器通道不接收任何目标 URL 参数。
        return sendOnce(simulatorClient, simulatorRoute.targetUri(), eventId,
                canonicalJsonUtf8, signingSecret); // 只能访问系统固定的本机路径。
    }

    private int sendOnce(CloseableHttpClient client, URI target, String eventId,
            byte[] canonicalJsonUtf8, String signingSecret) throws IOException { // 共享单次签名和发送行为。
        byte[] body = Objects.requireNonNull(canonicalJsonUtf8, "请求体缺失").clone(); // 固定签名和发送共用的原始字节。
        String timestamp = clock.instant().toString(); // 每次显式调用重新生成 UTC 时间戳。
        String signature = signer.sign(eventId, timestamp, body, signingSecret); // 对本次字节和时间重新签名。
        HttpPost request = new HttpPost(target); // 构造一条 POST 请求。
        request.setEntity(new ByteArrayEntity(body, ContentType.create("application/json"))); // 原样附上 JSON UTF-8 字节。
        request.setHeader("X-HookFlow-Event-Id", eventId); // 携带事件 ID。
        request.setHeader("X-HookFlow-Timestamp", timestamp); // 携带参与签名的时间戳。
        request.setHeader("X-HookFlow-Signature", signature); // 携带本次 HMAC 签名。
        try (ClassicHttpResponse response = client.executeOpen(null, request, null)) { // 建连、发送并保证响应关闭。
            return response.getCode(); // 此阶段仅返回状态码，不提前实现 P5-03 分类。
        }
    }

    @Override // 实现组件关闭时的资源释放。
    public void close() throws IOException { // 分别关闭两个互不共享的连接池。
        try { // 即使外部客户端关闭失败也继续清理内部客户端。
            externalClient.close(); // 释放外部连接资源。
        } finally { // 无论上一步结果如何都执行。
            simulatorClient.close(); // 释放模拟器连接资源。
        }
    }
}