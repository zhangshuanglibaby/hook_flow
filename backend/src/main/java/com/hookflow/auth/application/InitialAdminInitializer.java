package com.hookflow.auth.application; // 将首次初始化流程放在认证模块应用层。

import java.time.Clock; // 使用项目统一注入的可控 UTC 时钟。
import java.time.LocalDateTime; // 对应 MySQL 的 DATETIME(3) 时间值。
import java.time.ZoneOffset; // 明确把当前瞬间转换为 UTC。
import java.time.temporal.ChronoUnit; // 将写入时间截取到毫秒精度。
import org.springframework.beans.factory.annotation.Value; // 从现有配置读取初始账号和密码。
import org.springframework.boot.ApplicationArguments; // 接收启动运行器的方法参数。
import org.springframework.boot.ApplicationRunner; // 在应用启动、数据库迁移完成后执行初始化。
import org.springframework.context.annotation.Profile; // 排除没有数据库的 skeleton 配置组。
import org.springframework.security.crypto.password.PasswordEncoder; // 使用已注册的 Spring Security 密码编码器。
import org.springframework.stereotype.Component; // 将初始化器注册为 Spring 组件。
import com.hookflow.auth.infrastructure.AdminUserRepository; // 通过认证模块仓库读写管理员账户。

@Component // 正式启动时由 Spring 创建并执行此初始化器。
@Profile("!skeleton") // 无数据库的骨架测试不执行管理员初始化。
public class InitialAdminInitializer implements ApplicationRunner { // 只负责首次创建管理员。
    private final AdminUserRepository users; // 查询并插入管理员的数据库组件。
    private final PasswordEncoder passwordEncoder; // 将初始密码转换为不可直接登录的摘要。
    private final Clock clock; // 提供可测试的当前时间。
    private final String initialUsername; // 仅保存在运行中的配置值，不写入源码。
    private final String initialPassword; // 仅用于首次编码，不写入数据库明文字段。

    public InitialAdminInitializer( // 由 Spring 注入初始化所需依赖和环境配置。
            AdminUserRepository users, // 管理员账户数据库组件。
            PasswordEncoder passwordEncoder, // Spring Security 密码编码器。
            Clock clock, // 项目统一 UTC 时钟。
            @Value("${hookflow.admin.username}") String initialUsername, // 从环境变量占位符取得初始用户名。
            @Value("${hookflow.admin.password}") String initialPassword // 从环境变量占位符取得初始密码。
    ) { // 构造方法开始。
        this.users = users; // 保存数据库组件。
        this.passwordEncoder = passwordEncoder; // 保存密码编码器。
        this.clock = clock; // 保存时钟。
        this.initialUsername = initialUsername; // 保存启动时提供的用户名。
        this.initialPassword = initialPassword; // 保存启动时提供的密码，不输出日志。
    } // 构造方法结束。

    @Override // 实现 ApplicationRunner 的启动回调。
    public void run(ApplicationArguments args) { // 应用就绪前检查是否需要首次初始化。
        if (users.adminExists()) { // 数据库已有管理员时，不受当前环境变量的新值影响。
            return; // 不更新已有账户，也不重置已有密码。
        } // 首次创建条件检查结束。
        if (initialUsername.isBlank() || initialUsername.codePointCount(0, initialUsername.length()) > 64) { // 符合登录用户名的非空及长度限制。
            throw new IllegalStateException("配置错误：HOOKFLOW_ADMIN_USERNAME 必须为 1～64 个非全空白字符"); // 只报告变量名，不回显配置值。
        } // 用户名校验结束。
        if (initialPassword.isBlank() || initialPassword.codePointCount(0, initialPassword.length()) > 1024) { // 初始密码须符合登录字段长度限制。
            throw new IllegalStateException("配置错误：HOOKFLOW_ADMIN_PASSWORD 必须为 1～1024 个非全空白字符"); // 不将密码写入异常消息。
        } // 密码校验结束。
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); // 生成与数据库精度一致的 UTC 创建时间。
        users.createAdmin(initialUsername, passwordEncoder.encode(initialPassword), createdAt); // 只插入编码后的密码摘要。
    } // 启动初始化方法结束。
} // 初始化器结束。