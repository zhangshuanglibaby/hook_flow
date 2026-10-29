package com.hookflow.delivery.infrastructure; // 签名计算属于投递模块的基础设施层。

import java.nio.charset.StandardCharsets; // 固定签名输入和密钥文本使用 UTF-8。
import java.security.GeneralSecurityException; // 处理运行环境中的加密算法错误。
import java.util.HexFormat; // 将签名字节编码为小写十六进制。
import java.util.Objects; // 拒绝缺失的签名输入。
import javax.crypto.Mac; // 计算 HMAC-SHA256。
import javax.crypto.spec.SecretKeySpec; // 将密钥文本的字节交给 HMAC 算法。
import org.springframework.stereotype.Component; // 供后续单次投递客户端注入。

@Component // 注册无状态组件；每次调用都创建独立的 Mac 实例。
public final class DeliveryRequestSigner { // 只计算签名，不发送 HTTP 请求。
    public String sign(String eventId, String timestamp, byte[] rawBody, String signingSecret) { // 接收本次尝试的原始输入。
        Objects.requireNonNull(eventId, "事件 ID 缺失"); // 事件 ID 是签名首段。
        Objects.requireNonNull(timestamp, "时间戳缺失"); // 时间戳是签名第二段。
        Objects.requireNonNull(rawBody, "请求体缺失"); // 请求体必须使用实际发送的同一份字节。
        Objects.requireNonNull(signingSecret, "签名密钥缺失"); // 不允许无密钥签名。
        if (signingSecret.isEmpty()) { // 空密钥不能用于有效验签。
            throw new IllegalArgumentException("签名密钥缺失"); // 错误信息不包含密钥内容。
        } // 密钥检查结束。
        try { // 算法不可用时明确失败，不发送无签名请求。
            Mac mac = Mac.getInstance("HmacSHA256"); // 每次调用单独创建，避免共享可变加密状态。
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); // 使用交付的密钥文本作为 UTF-8 密钥。
            mac.update((eventId + "\n" + timestamp + "\n").getBytes(StandardCharsets.UTF_8)); // 依次写入事件 ID、换行、时间戳、换行。
            byte[] signature = mac.doFinal(rawBody); // 紧接前缀写入实际请求体字节并完成 HMAC。
            return HexFormat.of().formatHex(signature); // 返回 64 个小写十六进制字符，不添加前缀。
        } catch (GeneralSecurityException exception) { // 本地加密设施异常不代表 HTTP 已发送。
            throw new IllegalStateException("投递签名计算失败"); // 不在异常信息中暴露输入或密钥。
        } // 签名计算结束。
    } // 单次签名方法结束。
} // 签名组件结束。