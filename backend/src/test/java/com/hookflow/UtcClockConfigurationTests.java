package com.hookflow; // 放在应用根包，使测试找到 Spring Boot 应用配置。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较时区和 JSON 输出。
import java.time.Clock; // 使用应用注入的时钟类型。
import java.time.Instant; // 表示不依赖本地时区的时间点。
import java.time.ZoneOffset; // 使用标准 UTC 时区常量。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.beans.factory.annotation.Autowired; // 从容器注入实际配置的对象。
import org.springframework.boot.test.context.SpringBootTest; // 启动测试用 Spring 容器。
import org.springframework.test.context.ActiveProfiles; // 指定无数据库的测试配置组。
import tools.jackson.databind.json.JsonMapper; // 使用 Spring Boot 4 默认的 JSON 映射器。

@SpringBootTest // 验证应用实际注册的配置，而不是自行创建替代对象。
@ActiveProfiles("skeleton") // 此项检查不连接数据库。
class UtcClockConfigurationTests { // 定义 UTC 时钟和时间序列化测试。
    @Autowired // 请求 Spring 注入应用配置的 Clock。
    private Clock clock; // 保存注入的时钟，供测试使用。

    @Autowired // 请求 Spring 注入应用自动配置的 JSON 映射器。
    private JsonMapper jsonMapper; // 用实际映射器检查接口时间格式。

    @Test // 标记时钟测试。
    void injectedClockUsesUtc() { // 检查应用时钟的时区。
        assertEquals(ZoneOffset.UTC, clock.getZone()); // 时钟必须使用 UTC。
    } // 时钟测试结束。

    @Test // 标记时间格式测试。
    void serializesTimeAsUtcIso8601WithMilliseconds() { // 检查序列化后的 UTC 字符串。
        Instant instant = Instant.parse("2026-10-26T08:00:00.123Z"); // 固定时间使结果不受运行时刻影响。
        String json = jsonMapper.writeValueAsString(new TimeProbe(instant)); // 用应用映射器生成 JSON。
        assertEquals("{\"occurredAt\":\"2026-10-26T08:00:00.123Z\"}", json); // 必须保留毫秒并以 Z 标明 UTC。
    } // 时间格式测试结束。

    private record TimeProbe(Instant occurredAt) { // 仅用于测试时间字段的 JSON 形状。
    } // 测试数据类型结束。
} // 测试类结束。