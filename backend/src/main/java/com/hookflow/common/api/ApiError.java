package com.hookflow.common.api; // 将通用错误响应放在共享层，不归入某个业务模块。

public record ApiError(String code, String message, String requestId) { // 固定三个字段，对应 P0-02 的错误响应契约。
} // 记录类型结束；Java 会提供读取字段所需的方法。