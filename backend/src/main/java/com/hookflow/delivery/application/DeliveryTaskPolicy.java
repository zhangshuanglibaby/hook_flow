package com.hookflow.delivery.application; // 新任务使用的投递策略属于应用层。

import java.math.BigDecimal; // 精确表示数据库所需的秒数小数。
import java.time.Duration; // 保存解析后的时间间隔。
import org.springframework.boot.convert.DurationStyle; // 沿用现有配置校验器支持的时长格式。
import org.springframework.context.annotation.Profile; // skeleton 模式无需正式投递策略组件。
import org.springframework.core.env.Environment; // 读取已经生效的应用配置。
import org.springframework.stereotype.Component; // 让后续创建服务可以注入此策略。

@Profile("!skeleton") // 正式应用才创建此组件。
@Component // 由 Spring 在启动时读取并固定策略值。
public class DeliveryTaskPolicy { // 保存新建任务要复制的策略快照来源。
    private final int maxAttempts; // 每轮最多尝试的次数。
    private final BigDecimal retryDelay1Seconds; // 第一档重试等待秒数。
    private final BigDecimal retryDelay2Seconds; // 第二档重试等待秒数。

    public DeliveryTaskPolicy(Environment environment) { // 从应用实际生效的配置构造策略。
        String attempts = environment.getProperty("hookflow.delivery.max-attempts", "3"); // 读取最大尝试次数。
        try { // 拒绝不是整数的配置值。
            this.maxAttempts = Integer.parseInt(attempts); // 转换为任务表所需的整数。
        } catch (NumberFormatException exception) { // 配置无法转换时停止创建组件。
            throw new IllegalStateException("最大尝试次数配置无效", exception); // 不使用错误值继续运行。
        }
        if (maxAttempts < 1 || maxAttempts > 3) { // 与 V1 表的约束保持一致。
            throw new IllegalStateException("最大尝试次数必须为 1～3"); // 避免创建无法入库的任务。
        }

        String delays = environment.getProperty("hookflow.delivery.retry-delays", "10s,30s"); // 读取两档时长。
        String[] parts = delays.split(",", -1); // 保留空字段，以便识别漏填的时长。
        if (parts.length != 2) { // 必须恰好有两档。
            throw new IllegalStateException("重试间隔必须包含两档时长"); // 拒绝缺少或多出的配置。
        }
        this.retryDelay1Seconds = parseSeconds(parts[0]); // 固定第一档精确秒数。
        this.retryDelay2Seconds = parseSeconds(parts[1]); // 固定第二档精确秒数。
    }

    private static BigDecimal parseSeconds(String text) { // 将单档时长转换成数据库秒数。
        Duration duration; // 暂存解析后的时长。
        try { // 复用现有配置约定中的带单位格式，例如 10s。
            duration = DurationStyle.detectAndParse(text.trim()); // 解析时长而非自行截取单位。
        } catch (IllegalArgumentException exception) { // 空值或未知单位均属于配置错误。
            throw new IllegalStateException("重试间隔配置无效", exception); // 不创建错误的策略。
        }
        if (duration.isZero() || duration.isNegative()) { // 两档等待都必须严格大于零。
            throw new IllegalStateException("重试间隔必须大于零"); // 与任务表约束一致。
        }
        return BigDecimal.valueOf(duration.getSeconds()) // 取得整数秒部分。
                .add(BigDecimal.valueOf(duration.getNano(), 9)); // 加上纳秒小数部分，不经过浮点数。
    }

    public int maxAttempts() { // 供创建任务时读取次数快照。
        return maxAttempts; // 返回启动时固定的值。
    }

    public BigDecimal retryDelay1Seconds() { // 供创建任务时读取第一档。
        return retryDelay1Seconds; // BigDecimal 不可变，可直接返回。
    }

    public BigDecimal retryDelay2Seconds() { // 供创建任务时读取第二档。
        return retryDelay2Seconds; // BigDecimal 不可变，可直接返回。
    }
}