package com.hookflow.delivery.infrastructure; // 将连接时的 DNS 防护放在投递基础设施层。

import java.net.InetAddress; // 表示 DNS 解析得到的实际 IP 地址。
import java.net.InetSocketAddress; // 表示连接时使用的 IP 地址和端口。
import java.net.UnknownHostException; // 用解析失败阻止不安全连接。
import java.util.ArrayList; // 收集已经检查的连接地址。
import java.util.List; // 返回连接过程要使用的地址列表。
import java.util.Objects; // 检查解析器依赖是否存在。
import org.apache.hc.client5.http.DnsResolver; // 实现 Apache 客户端实际连接路径使用的解析接口。
import org.apache.hc.client5.http.SystemDefaultDnsResolver; // 使用系统默认 DNS 解析器。

final class GuardedDnsResolver implements DnsResolver { // 在每次连接解析时检查所有候选地址。
    private final DnsResolver delegate; // 保留真正执行 DNS 查询的解析器。

    GuardedDnsResolver() { // 供正式客户端使用的构造方法。
        this(SystemDefaultDnsResolver.INSTANCE); // 正式环境使用系统 DNS。
    }

    GuardedDnsResolver(DnsResolver delegate) { // 允许隔离测试注入可控解析结果。
        this.delegate = Objects.requireNonNull(delegate, "DNS 解析器缺失"); // 不允许跳过实际解析。
    }

    @Override // 实现 Apache 客户端的地址解析接口。
    public InetAddress[] resolve(String host) throws UnknownHostException { // 每次调用重新取得候选 IP。
        InetAddress[] resolved = delegate.resolve(host); // 对本次连接执行一次 DNS 解析。
        if (resolved == null || resolved.length == 0) { // 空结果不能用于连接。
            throw new UnknownHostException("投递目标没有可连接的地址"); // 在建立连接前失败。
        }
        InetAddress[] checked = resolved.clone(); // 固定本次实际要交给连接器的候选数组。
        for (InetAddress address : checked) { // 检查本次解析得到的每一个候选 IP。
            if (!PublicTargetAddressPolicy.isAllowed(address)) { // 只要有一个受保护地址就拒绝整批结果。
                throw new UnknownHostException("投递目标解析到受保护地址"); // 不向连接器返回任何候选 IP。
            }
        }
        return checked; // 仅返回刚刚逐个检查过的同一批 IP。
    }

    @Override // 明确控制 Apache 客户端按主机和端口解析的入口。
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException { // 为一次新连接生成地址列表。
        InetAddress[] checked = resolve(host); // 重新解析并检查本次连接的全部候选 IP。
        List<InetSocketAddress> destinations = new ArrayList<>(checked.length); // 为检查通过的结果预留空间。
        for (InetAddress address : checked) { // 不再次查询 DNS，也不替换候选 IP。
            destinations.add(new InetSocketAddress(address, port)); // 绑定本次连接端口与已检查的 IP。
        }
        return List.copyOf(destinations); // 把同一批已检查地址交给连接过程。
    }

    @Override // 实现接口要求的规范主机名方法。
    public String resolveCanonicalHostname(String host) { // 此方法不负责选择连接 IP。
        return host; // 保留原主机名，避免在这里触发另一条 DNS 查询路径。
    }
}