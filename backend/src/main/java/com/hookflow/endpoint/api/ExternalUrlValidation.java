package com.hookflow.endpoint.api; // URL 输入校验属于接收端 API 边界。

import java.net.URI; // 按结构解析 URL，不发起网络连接。
import java.net.URISyntaxException; // 识别不符合 URI 语法的输入。
import java.util.Locale; // 按固定规则处理主机名大小写。
import com.hookflow.common.api.TargetNotAllowedException; // 用专门类型标识安全策略拒绝的目标。
import org.springframework.http.HttpStatus; // 区分格式错误和禁止的目标。
import org.springframework.web.server.ResponseStatusException; // 接入现有统一错误响应机制。

public final class ExternalUrlValidation { // 仅校验 EXTERNAL 创建和编辑时的 URL。
    private ExternalUrlValidation() { // 工具类不允许实例化。
    }

    public static String validate(Object raw) { // 返回通过校验的原始 URL，供后续保存。
        if (!(raw instanceof String value) || value.isBlank()
                || value.codePointCount(0, value.length()) > 2048) { // 要求非空字符串且不超过契约长度。
            throw badRequest(); // 类型、空值和长度错误属于 400。
        }
        final URI uri; // 保存结构化解析结果。
        try {
            uri = new URI(value).parseServerAuthority(); // 要求 authority 能解析为服务器主机及端口。
        } catch (URISyntaxException exception) {
            throw badRequest(); // 不回显可能含凭据的原始 URL。
        }
        if (uri.isOpaque() || uri.getRawAuthority() == null
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawFragment() != null) { // 排除非层级地址、缺主机和片段。
            throw badRequest(); // 这些输入不是可保存的目标 URL 格式。
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) { // 协议只允许 HTTPS。
            throw targetNotAllowed(); // 语法合法但协议不允许，返回 422。
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null) { // 禁止用户信息及整个查询串。
            throw targetNotAllowed(); // 查询串可能携带无法可靠识别的凭据。
        }
        if (uri.getRawAuthority().endsWith(":")
                || uri.getPort() == 0 || uri.getPort() > 65535) { // 拒绝空端口和无效端口。
            throw badRequest(); // 端口格式不符合可用目标要求。
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT); // 主机名比较不区分大小写。
        if (host.startsWith("[") || host.matches("[0-9.]+")) { // 识别 IPv6 和纯数字 IPv4 字面形式。
            throw targetNotAllowed(); // 保守地拒绝所有 IP 字面地址，包含回环和私网。
        }
        if (host.equals("localhost") || host.length() > 253) { // 单机名不可作为外部公开目标。
            throw targetNotAllowed(); // 超长或本地主机不允许保存。
        }
        String[] labels = host.split("\\.", -1); // 保留空标签，以发现连续点或末尾点。
        if (labels.length < 2) { // 要求带有域名后缀的主机名。
            throw targetNotAllowed(); // 单标签内部主机名不得放行。
        }
        for (String label : labels) { // 逐个检查 DNS 标签。
            if (label.isEmpty() || label.length() > 63
                    || !Character.isLetterOrDigit(label.charAt(0))
                    || !Character.isLetterOrDigit(label.charAt(label.length() - 1))) {
                throw badRequest(); // 空标签、超长或首尾连字符均不是合法主机名。
            }
            for (int i = 0; i < label.length(); i++) { // 检查标签中的每个字符。
                char character = label.charAt(i); // 取得当前位置的字符。
                if (!((character >= 'a' && character <= 'z')
                        || (character >= '0' && character <= '9') || character == '-')) {
                    throw badRequest(); // 拒绝下划线等非 DNS 标签字符。
                }
            }
        }
        String suffix = labels[labels.length - 1]; // 取得域名最后一段。
        if (suffix.equals("local") || suffix.equals("internal")
                || suffix.equals("localhost") || suffix.equals("test")
                || suffix.equals("invalid") || suffix.equals("example")
                || suffix.equals("arpa") || suffix.equals("onion")) {
            throw targetNotAllowed(); // 排除常见内部或非公开用途的后缀。
        }
        if (suffix.charAt(0) >= '0' && suffix.charAt(0) <= '9') {
            throw targetNotAllowed(); // 避免数字形式主机名被误作公开域名。
        }
        return value; // 原样保留路径；本方法不解析 DNS，也不保证连接 IP 安全。
    }

    private static ResponseStatusException badRequest() { // 创建固定的格式错误。
        return new ResponseStatusException(HttpStatus.BAD_REQUEST); // 统一映射为 400。
    }

    private static TargetNotAllowedException targetNotAllowed() { // 创建不包含原始 URL 的目标安全异常。
        return new TargetNotAllowedException(); // 保持 422 状态，并供统一处理器识别错误代码。
    } // 目标安全错误构造结束。
}