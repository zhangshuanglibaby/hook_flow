package com.hookflow.support; // 将可复用的测试工具放在 support 包。

import java.time.Clock; // 继承应用使用的标准时钟类型。
import java.time.Duration; // 表示测试要推进的时间长度。
import java.time.Instant; // 表示当前的精确时间点。
import java.time.ZoneId; // 表示时钟使用的时区。
import java.time.ZoneOffset; // 提供默认的 UTC 时区。
import java.util.Objects; // 拒绝空的时间、时区或时长。
import java.util.concurrent.atomic.AtomicReference; // 安全地保存和更新时间点。

public final class MutableClock extends Clock { // 仅供测试控制时间的时钟。
    private final AtomicReference<Instant> current; // 保存可调整的当前时间。
    private final ZoneId zone; // 保存这个时钟视图使用的时区。

    public MutableClock(Instant initialInstant) { // 使用指定时间创建 UTC 时钟。
        this(new AtomicReference<>(Objects.requireNonNull(initialInstant)), ZoneOffset.UTC); // 初始化共享时间和 UTC 时区。
    } // 公共构造方法结束。

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) { // 为不同时区创建共享同一时间的视图。
        this.current = current; // 保留原有的可调整时间。
        this.zone = Objects.requireNonNull(zone); // 时区不能是空值。
    } // 私有构造方法结束。

    public void setInstant(Instant instant) { // 让测试直接指定当前时间。
        current.set(Objects.requireNonNull(instant)); // 原子地替换时间点。
    } // 设置时间的方法结束。

    public void advance(Duration duration) { // 让测试模拟时间流逝。
        Objects.requireNonNull(duration); // 拒绝空的时长。
        current.updateAndGet(instant -> instant.plus(duration)); // 在当前时间上原子地增加时长。
    } // 推进时间的方法结束。

    @Override // 实现 Clock 的时区读取方法。
    public ZoneId getZone() { // 返回此时钟视图的时区。
        return zone; // 默认实例返回 UTC。
    } // 时区读取方法结束。

    @Override // 实现 Clock 的时区切换方法。
    public Clock withZone(ZoneId newZone) { // 创建使用另一时区、但共享时间点的视图。
        return new MutableClock(current, newZone); // 调整时间后两个视图会看到相同的瞬间。
    } // 时区切换方法结束。

    @Override // 实现 Clock 的当前时间读取方法。
    public Instant instant() { // 获取测试指定的当前时间。
        return current.get(); // 不读取机器的真实系统时间。
    } // 时间读取方法结束。
} // 测试时钟类结束。