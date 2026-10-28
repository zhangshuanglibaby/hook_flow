package com.hookflow.endpoint.domain; // 任务配置快照的数据结构属于接收端领域层。

import java.util.Objects; // 检查快照中必需的目标类型。

public final class EndpointTaskSnapshot { // 保存创建任务时需要复制的接收端配置，不保存明文密钥。
    private final EndpointTargetType targetType; // 保存目标类型，供任务写入目标类型快照。
    private final String url; // 保存当时的 URL；模拟器目标允许为 null。
    private final int timeoutMs; // 保存当时的请求超时毫秒数。
    private final byte[] secretCiphertextSnapshot; // 只保存签名密钥密文，不保存明文。

    public EndpointTaskSnapshot(EndpointTargetType targetType, String url, int timeoutMs,
            byte[] secretCiphertextSnapshot) { // 从已锁定的接收端配置构造独立快照。
        this.targetType = Objects.requireNonNull(targetType, "接收端目标类型缺失"); // 拒绝缺失目标类型。
        if (timeoutMs < 1 || timeoutMs > 15000) { // 与现有数据库超时范围保持一致。
            throw new IllegalArgumentException("接收端超时不合法"); // 拒绝无法写入任务快照的超时值。
        } // 超时校验结束。
        if (secretCiphertextSnapshot == null || secretCiphertextSnapshot.length == 0) { // 不接受缺失或空密文。
            throw new IllegalStateException("签名密钥密文缺失"); // 绝不使用明文或占位值替代。
        } // 密文存在性校验结束。
        this.url = url; // 保留数据库中的原 URL，不在此提前实施 P3-03 校验。
        this.timeoutMs = timeoutMs; // 固定构造时的超时值，后续编辑不会改变它。
        this.secretCiphertextSnapshot = secretCiphertextSnapshot.clone(); // 复制数组，避免外部修改快照密文。
    } // 快照构造结束。

    public EndpointTargetType targetType() { // 提供任务创建时的目标类型。
        return targetType; // 返回不可变枚举值。
    } // 目标类型读取结束。

    public String url() { // 提供任务创建时的 URL。
        return url; // String 不可变；模拟器目标返回 null。
    } // URL 读取结束。

    public int timeoutMs() { // 提供任务创建时的超时。
        return timeoutMs; // 返回构造时固定的毫秒数。
    } // 超时读取结束。

    public byte[] secretCiphertextSnapshot() { // 仅向任务持久化边界提供密文字节。
        return secretCiphertextSnapshot.clone(); // 再次复制，防止调用方修改内部数组。
    } // 密文读取结束。

    @Override // 覆盖默认字符串表示，避免日后日志意外输出 URL 或密文。
    public String toString() { // 只显示可用于排查问题的非敏感字段。
        return "EndpointTaskSnapshot[targetType=" + targetType + ", timeoutMs=" + timeoutMs + "]"; // 不包含 URL、密文或明文。
    } // 安全字符串表示结束。
} // 任务配置快照定义结束。