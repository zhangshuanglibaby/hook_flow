package com.hookflow.delivery.infrastructure; // 将连接时的 DNS 防护放在投递基础设施层。

import java.net.InetAddress; // 表示 DNS 解析得到的实际 IP 地址。
import java.net.InetSocketAddress; // 表示连接时使用的 IP 地址和端口。
import java.net.UnknownHostException; // 用解析失败阻止不安全连接。
import java.util.ArrayList; // 收集已经检查的连接地址。
import java.util.List; // 返回连接过程要使用的地址列表。
import java.util.Objects; // 检查解析器依赖是否存在。
import org.apache.hc.client5.http.DnsResolver; // 实现客户端实际连接路径使用的解析接口。
import org.apache.hc.client5.http.SystemDefaultDnsResolver; // 使用系统默认 DNS 解析器。

final class GuardedDnsResolver implements DnsResolver { // 在每次连接解析时检查所有候选地址。
    private final DnsResolver delegate; // 保留真正执行 DNS 查询的解析器。

    GuardedDnsResolver() { // 供正式客户端使用的构造方法。
        this(SystemDefaultDnsResolver.INSTANCE); // 正式环境使用系统 DNS。
    } // 正式构造方法结束。

    GuardedDnsResolver(DnsResolver delegate) { // 允许隔离测试注入可控解析结果。
        this.delegate = Objects.requireNonNull(delegate, "DNS 解析器缺失"); // 不允许跳过实际解析。
    } // 测试构造方法结束。

    @Override // 实现 Apache 客户端的地址解析接口。
    public InetAddress[] resolve(String host) throws UnknownHostException { // 每次调用重新取得候选 IP。
        InetAddress[] resolved = delegate.resolve(host); // 对本次连接执行一次 DNS 解析。
        if (resolved == null || resolved.length == 0) { // 空结果不能用于连接。
            throw new UnknownHostException("投递目标没有可连接的地址"); // 不把普通解析失败说成策略拒绝。
        } // 空结果检查结束。
        InetAddress[] checked = resolved.clone(); // 固定实际交给连接器的候选数组。
        for (InetAddress address : checked) { // 检查本次解析得到的每一个候选 IP。
            if (!PublicTargetAddressPolicy.isAllowed(address)) { // 只要有受保护地址就拒绝整批结果。
                throw new ProtectedTargetAddressException(); // 用专门类型标记已证明未发送的策略拒绝。
            } // 单个地址检查结束。
        } // 全部候选地址检查结束。
        return checked; // 仅返回刚刚逐个检查过的同一批 IP。
    } // 地址解析方法结束。

    @Override // 明确控制按主机和端口解析的入口。
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException { // 为一次新连接生成地址列表。
        InetAddress[] checked = resolve(host); // 重新解析并检查本次连接的全部候选 IP。
        List<InetSocketAddress> destinations = new ArrayList<>(checked.length); // 为已检查的结果预留空间。
        for (InetAddress address : checked) { // 遍历本次通过检查的地址。
            destinations.add(new InetSocketAddress(address, port)); // 绑定端口与同一个已检查的 IP。
        } // 地址列表构造结束。
        return List.copyOf(destinations); // 把同一批已检查地址交给连接过程。
    } // 带端口解析方法结束。

    @Override // 实现接口要求的规范主机名方法。
    public String resolveCanonicalHostname(String host) { // 此方法不负责选择连接 IP。
        return host; // 不在这里触发另一条 DNS 查询路径。
    } // 规范主机名方法结束。

    static final class ProtectedTargetAddressException extends UnknownHostException { // 区分策略拒绝与普通 DNS 故障。
        ProtectedTargetAddressException() { // 只允许受控解析器在检查失败时创建。
            super("投递目标解析到受保护地址"); // 使用固定说明，不泄露请求或签名内容。
        } // 专用异常构造结束。
    } // 受保护地址异常定义结束。
} // 受控 DNS 解析器结束。