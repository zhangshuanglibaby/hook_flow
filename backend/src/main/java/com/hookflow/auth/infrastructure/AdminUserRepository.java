package com.hookflow.auth.infrastructure; // 将管理员账户的 SQL 操作放在认证模块基础设施层。

import java.time.LocalDateTime; // 对应数据库 DATETIME(3) 字段。
import java.util.Optional; // 表示用户名可能查不到管理员。
import org.springframework.context.annotation.Profile; // 用于指定此数据库组件只在正式配置中启用。
import org.springframework.jdbc.core.JdbcTemplate; // 使用项目现有的 Spring JDBC 执行 SQL。
import org.springframework.stereotype.Repository; // 将此类注册为数据库访问组件。

@Profile("!skeleton") // skeleton 测试没有数据源；正式启动时才创建此组件。
@Repository // 允许认证应用层通过构造器注入此对象。
public class AdminUserRepository { // 只负责管理员账户的数据库读写。
    private final JdbcTemplate jdbcTemplate; // 保存 Spring 提供的数据库操作对象。

    public AdminUserRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入数据库操作对象。
        this.jdbcTemplate = jdbcTemplate; // 后续方法共用同一个数据源。
    } // 构造方法结束。

    public boolean adminExists() { // 判断是否已经初始化过管理员。
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN'", Long.class); // 只统计管理员账户。
        return count != null && count > 0; // 已有管理员时，启动逻辑不得重建或重置密码。
    } // 存在性查询结束。

    public void createAdmin(String username, String passwordHash, LocalDateTime createdAt) { // 插入首次管理员；调用方必须提供摘要和 UTC 时间。
        jdbcTemplate.update("INSERT INTO app_user (username, password_hash, role, created_at) VALUES (?, ?, 'ADMIN', ?)", username, passwordHash, createdAt); // 参数绑定避免拼接用户名；不传入明文密码。
    } // 创建方法结束。

    public Optional<AdminCredentials> findByUsername(String username) { // 登录时按用户名读取管理员 ID 和密码摘要。
        return jdbcTemplate.query( // 查询结果使用列表接收，未找到时不会抛出异常。
                "SELECT id, password_hash FROM app_user WHERE username = ? AND role = 'ADMIN'", // 不读取或返回其他账户字段。
                (rs, rowNum) -> new AdminCredentials(rs.getLong("id"), rs.getString("password_hash")), // 将单行结果转换为登录所需数据。
                username // 以 SQL 参数传入用户名，不拼接到查询文本。
        ).stream().findFirst(); // 用户名有唯一约束，结果至多一条；没有则返回 Optional.empty()。
    } // 用户名查询结束。

    public record AdminCredentials(long userId, String passwordHash) { // 仅在认证模块内部传递 ID 和密码摘要。
    } // 查询结果类型结束。
} // 数据库访问类结束。