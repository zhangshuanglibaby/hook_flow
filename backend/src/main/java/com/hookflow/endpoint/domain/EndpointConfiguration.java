package com.hookflow.endpoint.domain; // 接收端配置的数据结构属于接收端领域层。

import java.time.Instant; // 用明确的时间点表示数据库中按 UTC 保存的时间。

public record EndpointConfiguration( // 仅承载管理查询所需配置，不承载签名密钥或密文。
        long id, // 数据库生成的接收端内部 ID。
        String name, // 管理员设置的显示名称。
        EndpointTargetType targetType, // 固定的外部目标或模拟器目标类型。
        String url, // 外部目标地址；模拟器目标为 null。
        int timeoutMs, // 单次请求超时毫秒数。
        boolean enabled, // 当前启用状态。
        Instant createdAt, // 接收端创建时刻。
        Instant updatedAt // 最近一次修改时刻。
) { // record 提供不可变的字段访问方法。
} // 非敏感接收端配置定义结束。