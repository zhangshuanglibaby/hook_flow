package com.hookflow.delivery.domain; // 单次尝试的结果规则属于投递领域层。

import java.util.Objects; // 用于拒绝缺失的结果类型。

public record DeliveryAttemptClassification(AttemptResult result, boolean retryable) { // 保存结果类型及重试资格。
    public enum AttemptResult { // 名称与 P0 契约的尝试结果一致，不包含尚未结束的 STARTED。
        SUCCESS, // 收到任意 2xx。
        HTTP_FAILURE, // 收到非成功的最终 HTTP 响应。
        UNKNOWN, // 无法确认接收端是否已经处理。
        LOCAL_FAILURE // 能明确证明请求尚未发送的本地错误。
    } // 结果枚举结束。

    public DeliveryAttemptClassification { // 校验构造出的分类不会自相矛盾。
        Objects.requireNonNull(result, "尝试结果缺失"); // 禁止空结果。
        if ((result == AttemptResult.SUCCESS || result == AttemptResult.LOCAL_FAILURE) && retryable) { // 成功和明确未发送均不重试。
            throw new IllegalArgumentException("该尝试结果不能标记为可重试"); // 拒绝错误的资格标记。
        } // 第一项校验结束。
        if (result == AttemptResult.UNKNOWN && !retryable) { // 结果未知始终具有重试资格。
            throw new IllegalArgumentException("结果未知必须保留重试资格"); // 实际次数限制留给后续任务处理。
        } // 第二项校验结束。
    } // 构造校验结束。

    public static DeliveryAttemptClassification fromHttpStatus(int status) { // 仅根据收到的状态码分类。
        if (status < 100 || status > 599) { // 拒绝不是 HTTP 状态码的输入。
            throw new IllegalArgumentException("HTTP 状态码不合法"); // 不把非法输入误记为目标失败。
        } // 状态码范围校验结束。
        if (status >= 200 && status < 300) { // 任意 2xx 都表示对方确认接收。
            return new DeliveryAttemptClassification(AttemptResult.SUCCESS, false); // 成功后不重试。
        } // 成功分支结束。
        if (status == 408 || status == 429 || status >= 500) { // 这三类响应具有重试资格。
            return new DeliveryAttemptClassification(AttemptResult.HTTP_FAILURE, true); // 不读取 Retry-After。
        } // 可重试 HTTP 失败分支结束。
        if (status >= 300) { // 剩余的 3xx 和 4xx 不具备重试资格。
            return new DeliveryAttemptClassification(AttemptResult.HTTP_FAILURE, false); // 后续执行器据此结束本轮。
        } // 不可重试 HTTP 失败分支结束。
        return unknown(); // 单独出现的 1xx 不是最终响应，不能判为成功或明确未发送。
    } // HTTP 分类方法结束。

    public static DeliveryAttemptClassification unknown() { // 供超时、断连等结果不明的路径使用。
        return new DeliveryAttemptClassification(AttemptResult.UNKNOWN, true); // 保留重试资格但不在此调度。
    } // 未知结果工厂结束。

    public static DeliveryAttemptClassification localFailure() { // 仅供明确未发送的本地拒绝路径使用。
        return new DeliveryAttemptClassification(AttemptResult.LOCAL_FAILURE, false); // 本地拒绝不重试。
    } // 本地失败工厂结束。
} // 分类定义结束。