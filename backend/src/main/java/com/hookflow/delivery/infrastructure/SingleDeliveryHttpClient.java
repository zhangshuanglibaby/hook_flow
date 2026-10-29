package com.hookflow.delivery.infrastructure; // 单次 HTTP 请求属于投递基础设施层。

import java.io.IOException; // 区分网络读取故障和已证明的地址拒绝。
import java.net.URI; // 结构化表示外部或固定内部目标。
import java.time.Clock; // 每次请求重新取得签名时间戳。
import java.util.Objects; // 校验必需的调用参数。
import java.util.concurrent.ExecutionException; // 识别执行线程的异常结果。
import java.util.concurrent.ExecutorService; // 管理单次 HTTP 调用的工作线程。
import java.util.concurrent.Executors; // 创建可关闭的虚拟线程执行器。
import java.util.concurrent.Future; // 等待一次 HTTP 调用完成。
import java.util.concurrent.TimeUnit; // 明确超时参数的毫秒单位。
import java.util.concurrent.TimeoutException; // 识别整体执行超时。
import org.apache.hc.client5.http.DnsResolver; // 为实际连接指定 DNS 解析器。
import org.apache.hc.client5.http.SystemDefaultDnsResolver; // 固定内部模拟器使用普通解析器。
import org.apache.hc.client5.http.classic.methods.HttpPost; // 构造单次 POST 请求。
import org.apache.hc.client5.http.config.RequestConfig; // 为本次请求配置连接、等待和读取超时。
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver; // 解析直连目标的默认端口。
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient; // 管理同步 HTTP 客户端。
import org.apache.hc.client5.http.impl.classic.HttpClients; // 创建两个独立客户端。
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder; // 安装实际连接使用的解析器。
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner; // 禁止代理路由。
import org.apache.hc.core5.http.ClassicHttpResponse; // 读取并关闭 HTTP 响应。
import org.apache.hc.core5.http.ContentType; // 指定 JSON 请求体类型。
import org.apache.hc.core5.http.HttpEntity; // 访问响应正文流。
import org.apache.hc.core5.http.io.entity.ByteArrayEntity; // 原样发送规范化 JSON 字节。
import org.apache.hc.core5.util.Timeout; // 表示 Apache 客户端的毫秒超时。
import org.springframework.beans.factory.annotation.Autowired; // 明确正式依赖注入构造方法。
import org.springframework.stereotype.Component; // 注册并管理客户端组件。

@Component // 由 Spring 管理客户端及其关闭动作。
public final class SingleDeliveryHttpClient implements AutoCloseable { // 每次显式调用最多发起一次 HTTP 请求。
    private final Clock clock; // 保存统一时钟。
    private final DeliveryRequestSigner signer; // 保存签名器。
    private final SimulatorInternalTargetRoute simulatorRoute; // 保存固定内部路由。
    private final CloseableHttpClient externalClient; // 外部目标使用带 DNS 防护的独立连接池。
    private final CloseableHttpClient simulatorClient; // 模拟器使用独立连接池。
    private final ExecutorService attempts; // 整体超时需要能限制同步网络调用的等待时间。

    @Autowired // 两个构造方法并存时选择正式注入入口。
    public SingleDeliveryHttpClient(Clock clock, DeliveryRequestSigner signer,
            SimulatorInternalTargetRoute simulatorRoute) { // 接收正式环境的三个组件。
        this(clock, signer, simulatorRoute, new GuardedDnsResolver()); // 外部连接始终启用受控 DNS。
    } // 正式构造方法结束。

    SingleDeliveryHttpClient(Clock clock, DeliveryRequestSigner signer,
            SimulatorInternalTargetRoute simulatorRoute, GuardedDnsResolver guardedResolver) { // 同包测试可注入可控 DNS。
        this.clock = Objects.requireNonNull(clock, "时钟缺失"); // 禁止缺失签名时间来源。
        this.signer = Objects.requireNonNull(signer, "签名器缺失"); // 禁止无签名投递。
        this.simulatorRoute = Objects.requireNonNull(simulatorRoute, "模拟器内部路由缺失"); // 内部目标不得来自用户 URL。
        this.externalClient = newClient(Objects.requireNonNull(guardedResolver, "受控 DNS 缺失")); // 外部连接只用受控解析。
        this.simulatorClient = newClient(SystemDefaultDnsResolver.INSTANCE); // 模拟器连接池与外部目标隔离。
        this.attempts = Executors.newVirtualThreadPerTaskExecutor(); // 每次调用独立等待，组件关闭时停止执行器。
    } // 测试构造方法结束。

    private static CloseableHttpClient newClient(DnsResolver resolver) { // 为目标类型建立独立连接池。
        return HttpClients.custom() // 创建同步 HTTP 客户端。
                .disableAutomaticRetries() // 网络故障也不能暗中再次发送。
                .disableRedirectHandling() // 3xx 不能跳到未经检查的地址。
                .disableContentCompression() // 禁止自动解压响应内容。
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)) // 不使用环境代理。
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create() // 建立实际连接管理器。
                        .setDnsResolver(resolver) // 在建连路径安装指定解析器。
                        .build()) // 完成此目标类型专用连接池。
                .build(); // 完成客户端创建，此时不发送请求。
    } // 客户端工厂结束。

    public SingleDeliveryHttpResult postOnce(String targetUrl, String eventId,
            byte[] canonicalJsonUtf8, String signingSecret, int timeoutMs) { // 外部目标使用任务超时快照调用一次。
        validateTimeout(timeoutMs); // 在发送前检查快照范围。
        try { // 以下参数和签名错误均发生在实际网络发送之前。
            URI target = URI.create(Objects.requireNonNull(targetUrl, "投递地址缺失")); // 结构化读取目标。
            if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null
                    || target.getRawUserInfo() != null || target.getRawQuery() != null
                    || target.getRawFragment() != null) { // 外部只接受无凭据、无查询串的 HTTPS 主机目标。
                return SingleDeliveryHttpResult.localFailure(); // 无效目标尚未建立连接。
            } // 外部目标校验结束。
            return sendOnce(externalClient, target, eventId, canonicalJsonUtf8,
                    signingSecret, timeoutMs); // 实际连接仍经过受控 DNS。
        } catch (IllegalArgumentException | NullPointerException exception) { // 只捕获请求构造前的本地输入错误。
            return SingleDeliveryHttpResult.localFailure(); // 请求尚未交给执行器。
        } // 外部单次调用结束。
    } // 外部投递入口结束。

    public SingleDeliveryHttpResult postSimulatorOnce(String eventId, // 接收本次投递的事件 ID。
            byte[] canonicalJsonUtf8, String signingSecret, int timeoutMs) { // 接收原始正文、签名密钥和任务超时快照。
        validateTimeout(timeoutMs); // 在任何发送动作之前校验超时快照范围。
        URI target; // 保存系统固定生成的本机模拟器地址。
        try { // 先单独检查内部路由，确保端口错误发生在发送之前。
            target = simulatorRoute.targetUri(); // 从应用监听端口生成固定地址，不使用用户 URL。
        } catch (IllegalStateException exception) { // 端口无效时，尚未创建或提交 HTTP 请求。
            return SingleDeliveryHttpResult.localFailure(); // 明确标记为未发送的本地失败。
        } // 内部路由检查结束。
        try { // 路由有效后才准备签名并提交单次请求。
            return sendOnce(simulatorClient, target, eventId, // 使用模拟器专用客户端和固定地址。
                    canonicalJsonUtf8, signingSecret, timeoutMs); // 将同一超时快照用于本次请求。
        } catch (IllegalArgumentException | NullPointerException exception) { // 捕获提交网络任务前的参数或签名错误。
            return SingleDeliveryHttpResult.localFailure(); // 这些错误发生在请求发送之前。
        } // 单次模拟器调用结束。
    } // 模拟器投递入口结束。

    private static void validateTimeout(int timeoutMs) { // 范围与任务快照数据库约束一致。
        if (timeoutMs < 1 || timeoutMs > 15000) { // 只接受 1～15000 毫秒。
            throw new IllegalArgumentException("任务超时快照必须在 1～15000 毫秒之间"); // 不以默认值覆盖错误快照。
        } // 超时范围检查结束。
    } // 超时校验结束。

    private SingleDeliveryHttpResult sendOnce(CloseableHttpClient client, URI target, // 指定本次使用的客户端和目标。
            String eventId, byte[] canonicalJsonUtf8, String signingSecret, // 接收事件 ID、原始正文和签名密钥。
            int timeoutMs) { // 接收本次任务的超时快照。
        byte[] body = Objects.requireNonNull(canonicalJsonUtf8, "请求体缺失").clone(); // 固定签名与发送共用的正文。
        String timestamp = clock.instant().toString(); // 为本次显式调用取得新时间戳。
        String signature = signer.sign(eventId, timestamp, body, signingSecret); // 对本次正文重新计算签名。
        HttpPost request = new HttpPost(target); // 创建一条 POST 请求。
        Timeout limit = Timeout.ofMilliseconds(timeoutMs); // 将快照毫秒数转成客户端超时值。
        request.setConfig(RequestConfig.custom() // 建立只作用于本次请求的配置。
                .setConnectionRequestTimeout(limit) // 限制等待连接池连接的时间。
                .setConnectTimeout(limit) // 限制建立连接的时间。
                .setResponseTimeout(limit) // 限制等待或读取响应的时间。
                .setHardCancellationEnabled(true) // 整体到期时允许中止网络交换。
                .build()); // 完成本次请求配置。
        request.setEntity(new ByteArrayEntity(body, ContentType.create("application/json"))); // 原样发送 JSON 字节。
        request.setHeader("X-HookFlow-Event-Id", eventId); // 设置事件 ID 请求头。
        request.setHeader("X-HookFlow-Timestamp", timestamp); // 设置参与签名的时间戳请求头。
        request.setHeader("X-HookFlow-Signature", signature); // 设置本次计算的签名请求头。
        java.util.concurrent.atomic.AtomicReference<SingleDeliveryHttpResult> knownResponse =
                new java.util.concurrent.atomic.AtomicReference<>(); // 跨线程保存已收到的最终状态码及其保守结果。
        Future<SingleDeliveryHttpResult> execution =
                attempts.submit(() -> executeOnce(client, request, knownResponse)); // 只提交这一条 HTTP 请求。
        try { // 等待请求和有限响应读取结束。
            return execution.get(timeoutMs, TimeUnit.MILLISECONDS); // 整体等待受同一快照限制。
        } catch (TimeoutException exception) { // 整体到期时停止继续等待。
            request.cancel(); // 尝试中止当前网络请求。
            execution.cancel(true); // 中断执行本次请求的工作线程。
            SingleDeliveryHttpResult received = knownResponse.get(); // 检查到期前是否已收到最终 HTTP 状态。
            return received == null ? SingleDeliveryHttpResult.unknown() : received; // 仅状态未知时才返回 UNKNOWN。
        } catch (InterruptedException exception) { // 调用方被中断时结束等待。
            Thread.currentThread().interrupt(); // 保留调用方的中断标记。
            request.cancel(); // 尝试中止当前网络请求。
            execution.cancel(true); // 中断执行本次请求的工作线程。
            SingleDeliveryHttpResult received = knownResponse.get(); // 检查是否已有明确的最终响应。
            return received == null ? SingleDeliveryHttpResult.unknown() : received; // 已知状态按状态判定。
        } catch (ExecutionException exception) { // 处理工作线程未预期的异常。
            if (exception.getCause() instanceof Error error) { // 识别不能吞掉的严重 JVM 错误。
                throw error; // 保留严重错误原有语义。
            } // 严重错误检查结束。
            SingleDeliveryHttpResult received = knownResponse.get(); // 检查异常前是否已有最终状态。
            return received == null ? SingleDeliveryHttpResult.unknown() : received; // 不丢弃已知 HTTP 结果。
        } // 整体等待结束。
    } // 单次请求准备和等待结束。

    private static SingleDeliveryHttpResult executeOnce(CloseableHttpClient client, // 指定实际执行请求的客户端。
            HttpPost request, // 接收已经配置好的单次 POST 请求。
            java.util.concurrent.atomic.AtomicReference<SingleDeliveryHttpResult> knownResponse) { // 回传已知响应。
        try (ClassicHttpResponse response = client.executeOpen(null, request, null)) { // 确保响应和连接最终关闭。
            SingleDeliveryHttpResult statusOnly =
                    SingleDeliveryHttpResult.fromHttpResponse(response.getCode(), ""); // 先仅按最终状态码分类。
            knownResponse.set(statusOnly); // 在读取可能停顿的正文前保存已确定的结果。
            HttpEntity entity = response.getEntity(); // 响应可能没有正文。
            String excerpt = entity == null ? "" : ResponseExcerptReader.read(entity.getContent()); // 最多读取 4096 字节。
            SingleDeliveryHttpResult completed =
                    SingleDeliveryHttpResult.fromHttpResponse(response.getCode(), excerpt); // 保存完整的有限摘要。
            knownResponse.set(completed); // 如读取成功，用带摘要的结果替换仅含状态的结果。
            return completed; // 返回此次最终 HTTP 状态和有限摘要。
        } catch (GuardedDnsResolver.ProtectedTargetAddressException exception) { // 地址策略在连接前明确拒绝。
            return SingleDeliveryHttpResult.localFailure(); // 此时可以证明请求尚未发送。
        } catch (IOException exception) { // 处理断连、超时、DNS 或正文读取异常。
            SingleDeliveryHttpResult received = knownResponse.get(); // 查看是否已收到最终 HTTP 状态。
            return received == null ? SingleDeliveryHttpResult.unknown() : received; // 仅没有最终状态时结果不明。
        } // 网络执行结束。
    } // 网络交换方法结束。

    @Override // 释放组件持有的工作线程与两个连接池。
    public void close() throws IOException { // Spring 关闭组件或测试结束时调用。
        attempts.shutdownNow(); // 中断仍未结束的工作线程。
        try { // 即使外部客户端关闭失败，也继续清理内部客户端。
            externalClient.close(); // 释放外部连接资源。
        } finally { // 无论上一步结果如何都执行。
            simulatorClient.close(); // 释放模拟器独立连接资源。
        } // 连接池关闭结束。
    } // 组件关闭方法结束。
} // 单次 HTTP 客户端结束。