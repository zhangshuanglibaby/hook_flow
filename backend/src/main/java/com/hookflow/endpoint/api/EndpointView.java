package com.hookflow.endpoint.api; // 接收端对管理员返回的数据结构属于 API 层。

import java.time.Instant; // 用时间点表示创建和更新时间，响应时序列化为 UTC 时间。
import com.hookflow.endpoint.domain.EndpointTargetType; // 使用已固定的两种接收端类型。

public record EndpointView( // 只列出允许对外显示的字段，不加入签名密钥或密文。
        long id, // 接收端在数据库中的内部 ID。
        String name, // 管理员设置的接收端名称。
        EndpointTargetType targetType, // EXTERNAL 或 SIMULATOR。
        String url, // 外部接收端的地址；模拟器接收端为 null。
        int timeoutMs, // 单次请求的超时时间，单位毫秒。
        boolean enabled, // 当前是否启用；本阶段不提供启停操作。
        Instant createdAt, // 接收端创建时刻。
        Instant updatedAt // 接收端最近修改时刻。
) { // record 自动提供只读字段访问方法和构造方法。
} // 安全响应结构定义结束。