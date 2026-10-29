package com.hookflow.delivery.infrastructure; // 将连接目标地址规则放在投递基础设施层。

import java.net.InetAddress; // 使用解析器返回的实际 IP 地址字节。

final class PublicTargetAddressPolicy { // 只供投递客户端的 DNS 防护使用。
    private PublicTargetAddressPolicy() { // 不允许创建无状态规则对象。
    }

    static boolean isAllowed(InetAddress address) { // 判断实际解析结果是否可用于外部连接。
        if (address == null) { // 缺少地址时必须拒绝。
            return false; // 不把空结果交给连接过程。
        }
        byte[] bytes = address.getAddress(); // 读取原始字节，避免依赖地址的文本写法。
        if (bytes.length == 4) { // 四个字节表示 IPv4。
            return isPublicIpv4(bytes); // 使用 IPv4 规则检查。
        }
        if (bytes.length != 16) { // 普通 IPv6 必须有十六个字节。
            return false; // 未知地址格式一律拒绝。
        }
        if (isIpv4Mapped(bytes)) { // 检查是否为 IPv4-mapped IPv6。
            return isPublicIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]}); // 对其中的 IPv4 部分应用相同规则。
        }
        if (!isAllocatedIpv6(bytes)) { // 只考虑已分配的普通全球单播网段。
            return false; // 拒绝未分配、保留、回环、私网及链路本地等地址。
        }
        return !ipv6Prefix(bytes, 23, 0x20, 0x01, 0x00) // 拒绝 2001::/23 协议特殊用途范围。
                && !ipv6Prefix(bytes, 28, 0x20, 0x01, 0x00, 0x20) // 拒绝 2001:20::/28。
                && !ipv6Prefix(bytes, 28, 0x20, 0x01, 0x00, 0x30) // 拒绝 2001:30::/28。
                && !ipv6Prefix(bytes, 32, 0x20, 0x01, 0x0d, 0xb8) // 拒绝 2001:db8::/32 文档地址。
                && !ipv6Prefix(bytes, 16, 0x20, 0x02) // 拒绝可嵌入 IPv4 目标的 6to4 地址。
                && !ipv6Prefix(bytes, 48, 0x26, 0x20, 0x00, 0x4f, 0x80, 0x00) // 拒绝 AS112 特殊地址。
                && !ipv6Prefix(bytes, 20, 0x3f, 0xff, 0x00); // 拒绝 3fff::/20 文档地址。
    }

    private static boolean isAllocatedIpv6(byte[] bytes) { // 根据已分配网段建立保守的允许列表。
        return ipv6Prefix(bytes, 23, 0x20, 0x01, 0x02) // 允许 2001:200::/23。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x04) // 合并允许 2001:400::/23 与 2001:600::/23。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x08) // 允许 2001:800::/22。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x0c) // 合并允许 2001:c00::/23 与 2001:e00::/23。
                || ipv6Prefix(bytes, 23, 0x20, 0x01, 0x12) // 允许 2001:1200::/23。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x14) // 允许 2001:1400::/22。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x18) // 合并允许 2001:1800::/23 与 2001:1a00::/23。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x1c) // 允许 2001:1c00::/22。
                || ipv6Prefix(bytes, 19, 0x20, 0x01, 0x20) // 允许 2001:2000::/19。
                || ipv6Prefix(bytes, 21, 0x20, 0x01, 0x40) // 合并允许 2001:4000:: 至 2001:4600:: 的四个 /23。
                || ipv6Prefix(bytes, 22, 0x20, 0x01, 0x48) // 合并允许 2001:4800::/23 与 2001:4a00::/23。
                || ipv6Prefix(bytes, 23, 0x20, 0x01, 0x4c) // 允许 2001:4c00::/23。
                || ipv6Prefix(bytes, 20, 0x20, 0x01, 0x50) // 允许 2001:5000::/20。
                || ipv6Prefix(bytes, 19, 0x20, 0x01, 0x80) // 允许 2001:8000::/19。
                || ipv6Prefix(bytes, 19, 0x20, 0x01, 0xa0) // 合并允许 2001:a000::/20 与 2001:b000::/20。
                || ipv6Prefix(bytes, 18, 0x20, 0x03, 0x00) // 允许 2003::/18。
                || ipv6Prefix(bytes, 12, 0x24, 0x00) // 允许 2400::/12。
                || ipv6Prefix(bytes, 12, 0x24, 0x10) // 允许 2410::/12。
                || ipv6Prefix(bytes, 12, 0x26, 0x00) // 允许 2600::/12。
                || ipv6Prefix(bytes, 23, 0x26, 0x10, 0x00) // 允许 2610::/23。
                || ipv6Prefix(bytes, 23, 0x26, 0x20, 0x00) // 允许 2620::/23。
                || ipv6Prefix(bytes, 12, 0x26, 0x30) // 允许 2630::/12。
                || ipv6Prefix(bytes, 12, 0x28, 0x00) // 允许 2800::/12。
                || ipv6Prefix(bytes, 12, 0x2a, 0x00) // 允许 2a00::/12。
                || ipv6Prefix(bytes, 12, 0x2a, 0x10) // 允许 2a10::/12。
                || ipv6Prefix(bytes, 12, 0x2c, 0x00); // 允许 2c00::/12；其他范围默认拒绝。
    }

    private static boolean isIpv4Mapped(byte[] bytes) { // 检查 IPv4-mapped IPv6 的固定前缀。
        for (int index = 0; index < 10; index++) { // 前十个字节必须全为零。
            if (bytes[index] != 0) { // 任一字节非零就不是 mapped 地址。
                return false; // 交由普通 IPv6 规则判断。
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff; // 接着必须是两个 FF 字节。
    }

    private static boolean isPublicIpv4(byte[] bytes) { // 检查 IPv4 是否落入不应外连的范围。
        int value = ((bytes[0] & 0xff) << 24) // 将第一个无符号字节放到最高位。
                | ((bytes[1] & 0xff) << 16) // 拼入第二个字节。
                | ((bytes[2] & 0xff) << 8) // 拼入第三个字节。
                | (bytes[3] & 0xff); // 拼入最后一个字节。
        if (Integer.compareUnsigned(value, 0xe0000000) >= 0) { // 224.0.0.0 及以上不是普通公开单播目标。
            return false; // 拒绝组播、保留范围及广播地址。
        }
        return !ipv4Prefix(value, 0x00000000, 8) // 拒绝 0.0.0.0/8。
                && !ipv4Prefix(value, 0x0a000000, 8) // 拒绝 10.0.0.0/8 私网。
                && !ipv4Prefix(value, 0x64400000, 10) // 拒绝 100.64.0.0/10 共享地址。
                && !ipv4Prefix(value, 0x7f000000, 8) // 拒绝 127.0.0.0/8 回环。
                && !ipv4Prefix(value, 0xa9fe0000, 16) // 拒绝 169.254.0.0/16 链路本地。
                && !ipv4Prefix(value, 0xac100000, 12) // 拒绝 172.16.0.0/12 私网。
                && !ipv4Prefix(value, 0xc0000000, 24) // 拒绝 192.0.0.0/24 特殊用途。
                && !ipv4Prefix(value, 0xc0000200, 24) // 拒绝 192.0.2.0/24 文档地址。
                && !ipv4Prefix(value, 0xc01fc400, 24) // 拒绝 192.31.196.0/24 特殊用途。
                && !ipv4Prefix(value, 0xc034c100, 24) // 拒绝 192.52.193.0/24 特殊用途。
                && !ipv4Prefix(value, 0xc0586300, 24) // 拒绝 192.88.99.0/24 特殊用途。
                && !ipv4Prefix(value, 0xc0a80000, 16) // 拒绝 192.168.0.0/16 私网。
                && !ipv4Prefix(value, 0xc0af3000, 24) // 拒绝 192.175.48.0/24 特殊用途。
                && !ipv4Prefix(value, 0xc6120000, 15) // 拒绝 198.18.0.0/15 基准测试地址。
                && !ipv4Prefix(value, 0xc6336400, 24) // 拒绝 198.51.100.0/24 文档地址。
                && !ipv4Prefix(value, 0xcb007100, 24); // 拒绝 203.0.113.0/24 文档地址。
    }

    private static boolean ipv4Prefix(int address, int network, int bits) { // 比较 IPv4 网段前缀。
        int mask = -1 << (32 - bits); // 生成指定长度的高位掩码。
        return (address & mask) == (network & mask); // 只比较网段位。
    }

    private static boolean ipv6Prefix(byte[] address, int bits, int... network) { // 比较 IPv6 网段前缀。
        int fullBytes = bits / 8; // 计算需要完整比较的字节数。
        for (int index = 0; index < fullBytes; index++) { // 逐字节核对前缀。
            if ((address[index] & 0xff) != network[index]) { // 任一完整字节不同即不在网段内。
                return false; // 结束本次网段匹配。
            }
        }
        int remainingBits = bits % 8; // 计算最后一个字节中还需比较的高位数。
        if (remainingBits == 0) { // 前缀恰好落在字节边界。
            return true; // 此前所有完整字节已经匹配。
        }
        int mask = (0xff << (8 - remainingBits)) & 0xff; // 仅保留末尾需要比较的高位。
        return (address[fullBytes] & mask) == (network[fullBytes] & mask); // 比较最后的部分字节。
    }
}