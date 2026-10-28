package com.hookflow.event.domain; // 规范化结果属于事件领域层。

import java.util.Arrays; // 用于比较两个指纹的字节内容。

public final class CanonicalEventPayload { // 表示一份规范化后的事件内容。
    private final String json; // 保存规范化 JSON 文本。
    private final byte[] fingerprint; // 保存该文本的 32 字节 SHA-256 指纹。

    CanonicalEventPayload(String json, byte[] fingerprint) { // 仅供同包的规范化器构造。
        if (json == null || fingerprint == null || fingerprint.length != 32) { // 拒绝缺失内容或长度错误的指纹。
            throw new IllegalArgumentException("Invalid canonical payload"); // 不把事件内容写入错误消息。
        }
        this.json = json; // String 不可变，可以直接保存。
        this.fingerprint = fingerprint.clone(); // 复制数组，防止调用方事后修改。
    }

    public String json() { // 提供将来写入 payload_json 的文本。
        return json; // 返回不可变的字符串。
    }

    public byte[] fingerprint() { // 提供将来写入 payload_fingerprint 的字节。
        return fingerprint.clone(); // 返回副本，避免内部数组被修改。
    }

    public boolean sameContent(CanonicalEventPayload other) { // 判断两份规范化结果是否相同。
        return other != null // 空对象不是相同内容。
                && Arrays.equals(fingerprint, other.fingerprint) // 首先核对指纹的字节内容。
                && json.equals(other.json); // 即使指纹相同，也必须核对规范化 JSON。
    }
}