package com.hookflow.config; // 将配置校验放在应用级公共包，不归入七个业务模块。

import java.time.Duration; // 用标准时间类型判断延迟是否大于零。
import org.springframework.boot.convert.DurationStyle; // 按 Spring Boot 支持的 10s 等格式解析时长。
import org.springframework.boot.EnvironmentPostProcessor; // 在应用上下文刷新前检查环境配置。
import org.springframework.boot.SpringApplication; // 接收当前启动的 Spring Boot 应用。
import org.springframework.core.env.ConfigurableEnvironment; // 读取启动时的环境变量。
import org.springframework.core.env.Profiles; // 判断是否启用了 skeleton 配置组。

public final class RequiredEnvironmentValidator implements EnvironmentPostProcessor { // 定义启动前的必填配置校验器。

    private static final String[] REQUIRED = { // 集中列出启动正式应用必须提供的变量名。
            "DB_URL", // MySQL JDBC 连接地址。
            "DB_USERNAME", // MySQL 用户名。
            "DB_PASSWORD", // MySQL 密码。
            "HOOKFLOW_ADMIN_USERNAME", // 首次初始化管理员的用户名。
            "HOOKFLOW_ADMIN_PASSWORD", // 首次初始化管理员的密码。
            "HOOKFLOW_SECRET_ENCRYPTION_KEY" // 加密接收端签名密钥的应用主密钥。
    }; // 变量名列表结束；这里不存放任何变量的真实值。

    @Override // 实现 Spring Boot 规定的环境处理方法。
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) { // 在创建数据库连接前执行检查。
        if (environment.acceptsProfiles(Profiles.of("skeleton"))) { // 骨架验证特意不依赖数据库和正式凭据。
            return; // 保留 P1-01 已验收的无数据库测试方式。
        } // skeleton 特例判断结束。

        for (String name : REQUIRED) { // 逐项检查正式启动所需的环境变量。
            String value = environment.getProperty(name); // 只读取值，不输出密码或密钥。
            if (value == null || value.isBlank()) { // 缺失、空字符串或只有空白都视为无效。
                throw new IllegalStateException("配置错误：必需环境变量 " + name + " 缺失或为空"); // 只报告变量名，不泄露其值。
            } // 单项检查结束。
        } // 全部必填变量检查结束。
        String rawMaxAttempts = environment.getProperty("hookflow.delivery.max-attempts", "3"); // 读取 YAML 中最终生效的尝试次数，未配置时用 3。
        int maxAttempts; // 保存解析后的整数，供范围检查使用。
        try { // 将配置文本转换为整数。
            maxAttempts = Integer.parseInt(rawMaxAttempts); // 非整数会进入下方的错误处理。
        } catch (NumberFormatException exception) { // 单独处理空值、字母或小数等非法格式。
            throw new IllegalStateException("配置错误：HOOKFLOW_MAX_ATTEMPTS 必须是 1～3 的整数", exception); // 给出可读的变量名和要求。
        } // 整数格式检查结束。
        if (maxAttempts < 1 || maxAttempts > 3) { // 最大尝试次数只允许 1、2、3。
            throw new IllegalStateException("配置错误：HOOKFLOW_MAX_ATTEMPTS 必须是 1～3 的整数"); // 越界时在启动阶段停止。
        } // 范围检查结束。
        String rawRetryDelays = environment.getProperty("hookflow.delivery.retry-delays", "10s,30s"); // 读取最终生效的两档延迟。
        String[] delayTexts = rawRetryDelays.split(",", -1); // 按逗号分开；保留末尾空项以便发现遗漏。
        if (delayTexts.length != 2) { // 必须恰好有首次失败后和第二次失败后两档。
            throw new IllegalStateException("配置错误：HOOKFLOW_RETRY_DELAYS 必须包含两档正数时长，例如 10s,30s"); // 拒绝少填或多填。
        } // 档数检查结束。
        for (String delayText : delayTexts) { // 分别检查每一档。
            Duration delay; // 保存解析后的时长。
            try { // 尝试解析带单位的配置文本。
                delay = DurationStyle.detectAndParse(delayText.trim()); // 例如把 10s 转换为 10 秒。
            } catch (IllegalArgumentException exception) { // 空值或无法识别的时长格式进入此处。
                throw new IllegalStateException("配置错误：HOOKFLOW_RETRY_DELAYS 必须包含两档正数时长，例如 10s,30s", exception); // 报告配置项，不输出凭据。
            } // 时长格式检查结束。
            if (delay.isZero() || delay.isNegative()) { // 零和负数不能作为重试等待时间。
                throw new IllegalStateException("配置错误：HOOKFLOW_RETRY_DELAYS 的两档时长都必须大于零"); // 启动时明确拒绝。
            } // 正数检查结束。
        } // 两档延迟检查结束。
    } // 环境处理方法结束。
} // 校验器定义结束。