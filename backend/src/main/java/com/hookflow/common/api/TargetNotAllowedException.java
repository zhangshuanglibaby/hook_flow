package com.hookflow.common.api; // 将可复用的目标安全异常放在统一 API 错误层。

import org.springframework.http.HttpStatus; // 使用 HTTP 422 状态。
import org.springframework.web.server.ResponseStatusException; // 交由现有统一异常处理器生成响应。

public final class TargetNotAllowedException extends ResponseStatusException { // 专门标识被安全策略禁止的外部目标。
    public TargetNotAllowedException() { // 调用方不传入可能含凭据的原始 URL。
        super(HttpStatus.UNPROCESSABLE_ENTITY); // 固定返回 422，不暴露请求内容。
    } // 构造方法结束。
} // 目标安全异常定义结束。