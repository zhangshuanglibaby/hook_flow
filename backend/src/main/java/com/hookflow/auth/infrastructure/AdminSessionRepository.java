package com.hookflow.auth.infrastructure; // 将管理员会话的 SQL 操作放在认证模块基础设施层。

import java.time.LocalDateTime; // 对应数据库中的 UTC 到期时间。
import java.util.Optional; // 表示 Token 可能不存在或已经失效。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置组。
import org.springframework.jdbc.core.JdbcTemplate; // 使用项目现有的 Spring JDBC。
import org.springframework.stereotype.Repository; // 注册数据库访问组件。

@Profile("!skeleton") // 仅在有真实数据库的正式配置中创建此组件。
@Repository // 让认证应用层能够注入此仓库。
public class AdminSessionRepository { // 负责保存和查询管理员会话。
    private final JdbcTemplate jdbcTemplate; // 执行使用参数绑定的 SQL。

    public AdminSessionRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入数据库操作对象。
        this.jdbcTemplate = jdbcTemplate; // 保存数据库操作对象供两个方法使用。
    } // 构造方法结束。

    public void create(long userId, byte[] tokenHash, LocalDateTime expiresAt) { // 保存用户 ID、Token 哈希与 UTC 到期时间。
        jdbcTemplate.update("INSERT INTO admin_session (user_id, token_hash, expires_at, revoked_at) VALUES (?, ?, ?, NULL)", userId, tokenHash, expiresAt); // 数据库不接收也不保存明文 Token。
    } // 会话创建方法结束。

    public Optional<Long> findActiveUserId(byte[] tokenHash, LocalDateTime now) { // 查询此刻仍有效的管理员会话。
        return jdbcTemplate.query( // 未查到时返回空列表，不把无效 Token 当作异常。
                "SELECT s.user_id FROM admin_session s JOIN app_user u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ? AND s.revoked_at IS NULL AND u.role = 'ADMIN'", // 同时检查到期、撤销状态和账户角色。
                (rs, rowNum) -> rs.getLong("user_id"), // 只返回已验证会话对应的管理员 ID。
                tokenHash, now // 使用哈希和调用方提供的 UTC 当前时间进行参数绑定。
        ).stream().findFirst(); // Token 哈希有唯一约束，至多返回一个管理员 ID。
    } // 有效会话查询结束。
} // 会话数据库访问类结束。