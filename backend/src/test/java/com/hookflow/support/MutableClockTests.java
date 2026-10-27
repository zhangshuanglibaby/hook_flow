package com.hookflow.support; // 将测试放在可控时钟所在的包中。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较实际时间和预期时间。
import java.time.Clock; // 验证时钟能作为应用使用的 Clock 类型。
import java.time.Duration; // 指定推进的时间长度。
import java.time.Instant; // 指定不受本地时区影响的时间点。
import java.time.ZoneId; // 创建另一个时区的时钟视图。
import java.time.ZoneOffset; // 检查默认时区是 UTC。
import org.junit.jupiter.api.Test; // 标记 JUnit 测试方法。

class MutableClockTests { // 验证测试专用时钟的行为。
    @Test // 让 JUnit 执行固定时间和推进时间的检查。
    void controlsCurrentInstant() { // 检查时钟不会依赖机器当前时间。
        Instant initial = Instant.parse("2026-10-27T08:00:00Z"); // 选定可重复的初始时间。
        MutableClock clock = new MutableClock(initial); // 创建默认使用 UTC 的可控时钟。
        assertEquals(ZoneOffset.UTC, clock.getZone()); // 默认时区必须是 UTC。
        assertEquals(initial, clock.instant()); // 初始时间必须与指定值一致。

        clock.advance(Duration.ofMinutes(5)); // 模拟时间向前经过五分钟。
        assertEquals(Instant.parse("2026-10-27T08:05:00Z"), clock.instant()); // 确认时间已推进。

        clock.setInstant(initial); // 将时钟重置到指定时间。
        assertEquals(initial, clock.instant()); // 确认重置结果。
    } // 第一个测试结束。

    @Test // 让 JUnit 验证不同时区的视图共享同一时间点。
    void otherZoneSharesControlledInstant() { // 检查切换时区后仍能控制同一时刻。
        Instant initial = Instant.parse("2026-10-27T08:00:00Z"); // 指定初始时间点。
        MutableClock clock = new MutableClock(initial); // 创建可调整的 UTC 时钟。
        Clock shanghaiView = clock.withZone(ZoneId.of("Asia/Shanghai")); // 创建同一时钟的上海时区视图。
        assertEquals(ZoneId.of("Asia/Shanghai"), shanghaiView.getZone()); // 确认新视图的时区。
        assertEquals(initial, shanghaiView.instant()); // 时区改变不能改变实际瞬间。

        clock.advance(Duration.ofSeconds(30)); // 通过原始时钟推进三十秒。
        assertEquals(clock.instant(), shanghaiView.instant()); // 两个视图必须看到同一更新后的瞬间。
    } // 第二个测试结束。
} // 测试类结束。