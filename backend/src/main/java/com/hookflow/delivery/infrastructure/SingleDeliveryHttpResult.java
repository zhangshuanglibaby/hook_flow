package com.hookflow.delivery.infrastructure; // 单次 HTTP 调用的结果属于投递基础设施层。

import com.hookflow.delivery.domain.DeliveryAttemptClassification; // 复用已验证的 P0 结果分类。
import com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult; // 检查结果类型与 HTTP 状态是否一致。
import java.nio.charset.StandardCharsets; // 按数据库要求检查摘要的 UTF-8 字节数。
import java.util.Objects; // 拒绝缺失的必需结果。

public record SingleDeliveryHttpResult( // 保存一次调用的结果，不执行调度或数据库写入。
        DeliveryAttemptClassification classification, // 保存结果类型和重试资格。
        Integer httpStatus, // 收到最终 HTTP 响应时保存状态码，否则为 null。
        String responseExcerpt) { // 收到最终响应时保存有限摘要，否则为 null。

    public SingleDeliveryHttpResult { // 阻止构造自相矛盾或超出数据库限制的结果。
        Objects.requireNonNull(classification, "结果分类缺失"); // 每次调用都必须有明确分类。
        if (httpStatus == null) { // 没有最终 HTTP 响应时只能是网络未知或本地拒绝。
            if (classification.result() != AttemptResult.UNKNOWN
                    && classification.result() != AttemptResult.LOCAL_FAILURE) { // 成功或 HTTP 失败必须有状态码。
                throw new IllegalArgumentException("该结果必须包含 HTTP 状态码"); // 拒绝缺少状态的响应结果。
            } // 无状态结果类型检查结束。
            if (responseExcerpt != null) { // 没有最终响应时不能声称取得了响应摘要。
                throw new IllegalArgumentException("没有 HTTP 状态码时不能包含响应摘要"); // 拒绝矛盾数据。
            } // 无状态摘要检查结束。
        } else { // 收到最终 HTTP 响应时检查状态和摘要。
            if (httpStatus < 200 || httpStatus > 599) { // 1xx 不是本对象表示的最终响应。
                throw new IllegalArgumentException("最终 HTTP 状态码不合法"); // 拒绝无效状态。
            } // 最终状态范围检查结束。
            DeliveryAttemptClassification expected = DeliveryAttemptClassification.fromHttpStatus(httpStatus); // 按 P0 契约重新判定。
            if (!expected.equals(classification)) { // 不允许调用方把状态码和重试资格搭错。
                throw new IllegalArgumentException("HTTP 状态码与结果分类不一致"); // 拒绝矛盾分类。
            } // 状态与分类一致性检查结束。
            Objects.requireNonNull(responseExcerpt, "响应摘要缺失"); // 空正文应使用空字符串表示。
            if (responseExcerpt.getBytes(StandardCharsets.UTF_8).length > ResponseExcerptReader.MAX_BYTES) { // 数据库按字节限制摘要。
                throw new IllegalArgumentException("响应摘要超过 4096 字节"); // 不允许超限内容流入后续持久化。
            } // 摘要大小检查结束。
        } // 有无最终响应的检查结束。
    } // 构造校验结束。

    public static SingleDeliveryHttpResult fromHttpResponse(int status, String excerpt) { // 构造已收到最终响应的结果。
        return new SingleDeliveryHttpResult(DeliveryAttemptClassification.fromHttpStatus(status), status, excerpt); // 状态决定结果，不读取 Retry-After。
    } // HTTP 响应工厂结束。

    public static SingleDeliveryHttpResult unknown() { // 构造无法确认是否送达的网络结果。
        return new SingleDeliveryHttpResult(DeliveryAttemptClassification.unknown(), null, null); // 不把超时或断连误判为未发送。
    } // 未知结果工厂结束。

    public static SingleDeliveryHttpResult localFailure() { // 仅供能够证明尚未发送的拒绝路径使用。
        return new SingleDeliveryHttpResult(DeliveryAttemptClassification.localFailure(), null, null); // 本地拒绝没有 HTTP 响应。
    } // 本地失败工厂结束。

    @Override // 避免日志意外输出可能含敏感内容的响应摘要。
    public String toString() { // 仅暴露分类和状态码用于安全排查。
        return "SingleDeliveryHttpResult[classification=" + classification
                + ", httpStatus=" + httpStatus + ", responseExcerpt=<redacted>]"; // 始终隐藏正文摘要。
    } // 安全字符串表示结束。
} // 单次 HTTP 结果定义结束。