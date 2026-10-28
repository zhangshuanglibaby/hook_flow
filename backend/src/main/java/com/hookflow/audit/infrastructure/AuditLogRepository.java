package com.hookflow.audit.infrastructure; // 审计记录的数据库访问代码归属 audit 基础设施层。

import java.time.LocalDateTime; // 对应 audit_log.occurred_at 的 UTC DATETIME(3) 值。
import org.springframework.context.annotation.Profile; // 没有数据库的 skeleton 配置不创建本组件。
import org.springframework.jdbc.core.JdbcTemplate; // 使用项目现有的 JDBC 访问方式。
import org.springframework.stereotype.Repository; // 将仓库注册为 Spring 组件。

@Profile("!skeleton") // 仅在有数据库的配置中启用。
@Repository // 允许审计应用服务通过构造器注入此仓库。
public class AuditLogRepository { // 只负责将已确定的审计字段写入数据库。
    private final JdbcTemplate jdbcTemplate; // 使用当前事务绑定的数据库连接。

    public AuditLogRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 提供 JDBC 模板。
        this.jdbcTemplate = jdbcTemplate; // 保存依赖供插入方法使用。
    } // 构造方法结束。

    public void insert( // 调用方应在成功管理操作的同一事务中调用。
            long actorUserId, // 已认证管理员的数据库内部 ID，不是 Token。
            String action, // 由后续应用服务限定的固定操作名称。
            String objectType, // 由后续应用服务限定的固定对象类型。
            String objectId, // 对象标识，不得传入凭据或密钥。
            LocalDateTime occurredAt, // 应用统一 UTC 时钟产生的毫秒精度时间。
            String requestId // 服务端生成的本次请求关联 ID。
    ) { // 开始插入一条成功操作记录。
        int rows = jdbcTemplate.update( // 参数化写入，不拼接用户输入。
                "INSERT INTO audit_log (actor_user_id, action, object_type, object_id, occurred_at, request_id) VALUES (?, ?, ?, ?, ?, ?)", // 字段顺序与参数顺序一致。
                actorUserId, action, objectType, objectId, occurredAt, requestId // 只传审计表允许保存的六个字段。
        ); // JDBC 写入结束。
        if (rows != 1) { // 成功操作必须对应恰好一条审计记录。
            throw new IllegalStateException("审计记录写入失败"); // 让调用方事务回滚，错误消息不含敏感值。
        } // 写入行数检查结束。
    } // 插入方法结束。
} // 审计仓库结束。