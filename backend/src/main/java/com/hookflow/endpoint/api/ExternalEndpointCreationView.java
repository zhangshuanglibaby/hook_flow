package com.hookflow.endpoint.api; // 一次性创建响应属于接收端 API 层。

import java.time.Instant; // 将创建和修改时间序列化为 UTC 时间点。
import com.hookflow.endpoint.domain.EndpointTargetType; // 复用已有的接收端类型枚举。

public record ExternalEndpointCreationView( // 仅供 EXTERNAL 创建成功的 201 响应使用。
        long id, // 新建接收端的数据库 ID。
        String name, // 已保存的接收端名称。
        EndpointTargetType targetType, // 固定为实际保存的 EXTERNAL 类型。
        String url, // 已保存且通过校验的外部目标 URL。
        int timeoutMs, // 已保存的超时毫秒数。
        boolean enabled, // 已保存的启用状态。
        Instant createdAt, // 接收端创建时间。
        Instant updatedAt, // 接收端最近修改时间。
        String signingSecret // 只在此次成功创建的响应中交付明文密钥。
) { // record 字段将直接成为 JSON 响应的同名字段。
    public static ExternalEndpointCreationView of(EndpointView view, String signingSecret) { // 从安全配置视图组装一次性响应。
        return new ExternalEndpointCreationView( // 显式列出所有允许返回的字段。
                view.id(), view.name(), view.targetType(), view.url(), // 复制标识和目标配置。
                view.timeoutMs(), view.enabled(), view.createdAt(), view.updatedAt(), // 复制状态和时间。
                signingSecret // 只追加本次新建 EXTERNAL 的明文密钥。
        ); // 一次性响应组装结束。
    } // 创建响应工厂方法结束。

    @Override // 避免 record 默认字符串形式意外打印密钥。
    public String toString() { // 日志若误调用 toString，也不显示密钥或 URL。
        return "ExternalEndpointCreationView[REDACTED]"; // 固定返回脱敏标记。
    } // 安全字符串表示结束。
} // 一次性创建响应定义结束。