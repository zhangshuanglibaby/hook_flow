package com.hookflow.event.api; // 事件 HTTP 请求的字段校验属于 API 层。

import com.hookflow.event.domain.CanonicalEventPayload; // 使用 P4-01 已生成的规范化内容。
import org.springframework.http.HttpStatus; // 指定非法输入的 HTTP 状态。
import org.springframework.web.server.ResponseStatusException; // 复用现有统一错误处理。
import tools.jackson.databind.JsonNode; // 读取已解析的 JSON 字段。

public final class EventSubmissionInput { // 保存通过接口字段校验的事件输入。
    private final String eventId; // 保留调用方提供的原始事件 ID，不擅自裁剪空格。
    private final String eventType; // 保留调用方提供的原始事件类型。
    private final long endpointId; // 保存已校验为正数的接收端 ID。
    private final CanonicalEventPayload payload; // 保存规范化 JSON 及 SHA-256 指纹。

    private EventSubmissionInput(String eventId, String eventType, long endpointId,
            CanonicalEventPayload payload) { // 仅允许本类在完成校验后构造。
        this.eventId = eventId; // 固定已校验的事件 ID。
        this.eventType = eventType; // 固定已校验的事件类型。
        this.endpointId = endpointId; // 固定已校验的接收端 ID。
        this.payload = payload; // 复用 P4-01 的不可变规范化结果。
    }

    public static EventSubmissionInput from(EventRequestJsonParser.ParsedEventJson parsed) { // 校验解析器返回的整份请求。
        if (parsed == null) { // 不接受缺失的解析结果。
            throw badRequest(); // 交给统一异常处理器返回 400。
        }
        JsonNode root = parsed.request(); // 获取已经确认是 JSON 对象的请求根节点。
        if (root.propertyNames().stream().anyMatch(key -> // 检查每个实际出现的字段名。
                !key.equals("eventId") && !key.equals("eventType") // 允许两个必填字符串字段。
                        && !key.equals("endpointId") && !key.equals("payload"))) { // 另允许接收端 ID 和 payload。
            throw badRequest(); // 未知字段不能被静默忽略。
        }

        String eventId = requiredText(root.get("eventId")); // 校验事件 ID 的类型、非空白和长度。
        String eventType = requiredText(root.get("eventType")); // 对事件类型使用相同的契约规则。
        long endpointId = positiveLong(root.get("endpointId")); // 仅接受 Java long 范围内的 JSON 正整数。
        return new EventSubmissionInput(eventId, eventType, endpointId, parsed.payload()); // 暂不访问数据库。
    }

    private static String requiredText(JsonNode node) { // 两个字符串字段共用同一校验规则。
        if (node == null || !node.isTextual()) { // 缺失、null、数字、数组等都不是字符串。
            throw badRequest(); // 必填字段类型错误返回 400。
        }
        String value = node.asString(); // 取得 JSON 字符串的实际内容。
        int length = value.codePointCount(0, value.length()); // 按 Unicode 码点而非 UTF-16 单元计数。
        if (length < 1 || length > 128 || value.isBlank()) { // 限制为 1～128 个码点，且不可全是空白。
            throw badRequest(); // 不在异常消息中回显事件 ID 或类型。
        }
        return value; // 保留合法输入的原值，供后续精确比较。
    }

    private static long positiveLong(JsonNode node) { // 校验接收端数据库 ID。
        if (node == null || !node.isIntegralNumber()) { // 字符串、小数、指数写法及 null 均不能冒充 JSON 整数。
            throw badRequest(); // 类型错误返回 400。
        }
        try { // 大整数转换为 long 时可能超出范围。
            long value = node.bigIntegerValue().longValueExact(); // 超出 Java long 范围会抛出异常。
            if (value <= 0) { // 接收端 ID 必须大于零。
                throw badRequest(); // 零和负数返回 400。
            }
            return value; // 返回范围内的正整数。
        } catch (ArithmeticException exception) { // 输入是整数，但超出了 long 范围。
            throw badRequest(); // 不截断，也不写入数据库。
        }
    }

    private static ResponseStatusException badRequest() { // 集中创建安全的字段错误。
        return new ResponseStatusException(HttpStatus.BAD_REQUEST); // 沿用 INVALID_REQUEST 错误映射。
    }

    public String eventId() { // 供后续创建用例读取事件 ID。
        return eventId;
    }

    public String eventType() { // 供后续创建用例读取事件类型。
        return eventType;
    }

    public long endpointId() { // 供后续创建用例读取接收端 ID。
        return endpointId;
    }

    public CanonicalEventPayload payload() { // 供后续创建用例读取规范化内容和指纹。
        return payload;
    }
}