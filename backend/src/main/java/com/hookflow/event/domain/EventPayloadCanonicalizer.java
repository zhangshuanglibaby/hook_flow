package com.hookflow.event.domain; // 将内容规范化规则放在事件领域层。

import java.math.BigDecimal; // 精确表示 JSON 数值，避免 double 精度损失。
import java.nio.charset.StandardCharsets; // 明确以 UTF-8 字节计算指纹。
import java.security.MessageDigest; // 提供 SHA-256 摘要计算。
import java.security.NoSuchAlgorithmException; // 处理运行环境缺少 SHA-256 的情况。
import java.util.ArrayList; // 创建可排序的对象键列表。
import java.util.Collections; // 对对象键进行稳定排序。
import java.util.List; // 表示对象键列表。
import tools.jackson.core.JacksonException; // 处理 JSON 序列化错误。
import tools.jackson.databind.JsonNode; // 使用 Jackson 的 JSON 树，而不拼接原始 JSON。
import tools.jackson.databind.json.JsonMapper; // 将规范化的树序列化为 JSON。
import tools.jackson.databind.node.ArrayNode; // 构造保持原顺序的数组。
import tools.jackson.databind.node.JsonNodeFactory; // 构造规范化后的节点。
import tools.jackson.databind.node.ObjectNode; // 构造按键序插入的对象。

public final class EventPayloadCanonicalizer { // 只负责一份 payload 的规范化与指纹。
    private final JsonMapper mapper = new JsonMapper(); // 序列化规范化树，不负责解析请求体。

    public CanonicalEventPayload canonicalize(JsonNode payload) { // 接收已经解析出的 payload 节点。
        JsonNode normalized = normalize(payload); // 递归处理对象、数组和数值。
        try { // 序列化可能报告 Jackson 错误。
            String json = mapper.writeValueAsString(normalized); // 生成无多余空白的规范化 JSON。
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8); // 固定指纹输入为该 JSON 的 UTF-8 字节。
            byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes); // 计算 32 字节指纹。
            return new CanonicalEventPayload(json, fingerprint); // 将文本与指纹组成不可变结果。
        } catch (JacksonException | NoSuchAlgorithmException exception) { // 这些属于内部处理失败，而非用户 JSON 错误。
            throw new IllegalStateException("Cannot canonicalize JSON payload", exception); // 不把 payload 写入错误消息。
        }
    }

    private JsonNode normalize(JsonNode value) { // 对 JSON 树的每一层应用同一规则。
        if (value == null || value.isMissingNode()) { // Java null 或缺失节点不等于合法的 JSON null。
            throw new IllegalArgumentException("Missing JSON payload"); // 拒绝缺失 payload。
        }
        if (value.isObject()) { // JSON 对象需要排序其字段名。
            ObjectNode result = JsonNodeFactory.instance.objectNode(); // 创建一个新的对象，避免修改原树。
            List<String> keys = new ArrayList<>(value.propertyNames()); // 收集当前对象的所有字段名。
            Collections.sort(keys); // 按 Java 字符串自然顺序排列字段名。
            for (String key : keys) { // 按排序后的顺序逐项写入。
                result.set(key, normalize(value.get(key))); // 字段值继续递归规范化。
            }
            return result; // 返回键序固定的新对象。
        }
        if (value.isArray()) { // JSON 数组的元素位置有意义。
            ArrayNode result = JsonNodeFactory.instance.arrayNode(); // 创建新数组，不修改原树。
            for (JsonNode element : value) { // 按原来的元素顺序遍历。
                result.add(normalize(element)); // 只规范化元素内部，不排序数组。
            }
            return result; // 返回保留原顺序的新数组。
        }
        if (value.isNumber()) { // 整数、小数及指数写法统一按精确数值处理。
            BigDecimal number = new BigDecimal(value.asString()).stripTrailingZeros(); // 消除无意义的末尾零。
            return JsonNodeFactory.instance.numberNode(number); // 用统一的十进制数值节点表示。
        }
        if (value.isTextual() || value.isBoolean() || value.isNull()) { // 其余三类合法 JSON 标量无需改动。
            return value; // 字符串、布尔值和真正的 JSON null 保持原值。
        }
        throw new IllegalArgumentException("Unsupported JSON payload"); // 拒绝不属于 JSON 的特殊节点。
    }
}