package com.hookflow.audit.application; // 成功管理操作的审计约定归属应用层。

import java.time.LocalDateTime; // 对应数据库的 DATETIME(3) 时间字段。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.stereotype.Service; // 将审计服务注册为 Spring 组件。
import org.springframework.transaction.annotation.Propagation; // 要求调用方已有事务。
import org.springframework.transaction.annotation.Transactional; // 声明审计写入的事务参与方式。
import com.hookflow.audit.infrastructure.AuditLogRepository; // 调用审计模块自己的数据库仓库。

@Profile("!skeleton") // 仅在有数据库的配置中启用。
@Service // 供各管理操作的应用服务注入。
public class AuditLogService { // 提供可复用的成功操作审计入口。
    private final AuditLogRepository auditLogs; // 保存审计记录仓库。

    public AuditLogService(AuditLogRepository auditLogs) { // Spring 只需注入审计仓库，时间由管理操作所在事务提供。
        this.auditLogs = auditLogs; // 保存仓库，用于写入审计行。
    } // 构造方法结束。

    @Transactional(propagation = Propagation.MANDATORY) // 必须加入管理操作已有事务，不能单独提交成功审计。
    public void recordSuccess(long actorUserId, Action action, long objectId, LocalDateTime occurredAt, String requestId) { // 接收同一事务已取得的 UTC 时间和服务端请求 ID。
        if (actorUserId <= 0 || objectId <= 0 || action == null || occurredAt == null) { // 拒绝无效操作者、对象、操作或时间。
            throw new IllegalArgumentException("审计参数不合法"); // 固定消息不回显调用方输入。
        } // 基本审计参数检查结束。
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) { // 只接受现有服务端过滤器生成的规范 UUID 格式。
            throw new IllegalArgumentException("审计请求 ID 不合法"); // 不回显可能包含敏感值的输入。
        } // 请求 ID 检查结束。
        auditLogs.insert(actorUserId, action.name(), action.objectType, Long.toString(objectId), occurredAt, requestId); // 将同一事务的 UTC 时间与限定字段写入审计表。
    } // 成功操作审计结束。

    public enum Action { // 固定允许记录的关键管理操作及其对象类型。
        API_KEY_ROTATED("BUSINESS_API_KEY"), // 业务 Key 轮换；对象 ID 约定为逻辑上的单个业务 Key 槽位 1。
        ENDPOINT_CREATED("ENDPOINT"), // 接收端创建；对象 ID 为创建后的数据库内部 ID。
        ENDPOINT_UPDATED("ENDPOINT"), // 接收端编辑；对象 ID 为接收端内部 ID。
        ENDPOINT_ENABLED("ENDPOINT"), // 接收端启用；对象 ID 为接收端内部 ID。
        ENDPOINT_DISABLED("ENDPOINT"), // 接收端停用；对象 ID 为接收端内部 ID。
        EVENT_REDELIVERED("EVENT"), // 人工重投；对象 ID 为事件的数据库内部 ID。
        SIMULATOR_CONFIG_UPDATED("SIMULATOR_CONFIG"); // 模拟器配置更新；对象 ID 为配置行 ID 1。

        private final String objectType; // 对象类型由枚举固定，不接受请求中的任意文本。

        Action(String objectType) { // 为每种操作绑定固定对象类型。
            this.objectType = objectType; // 保存已审查过的类型名称。
        } // 枚举构造方法结束。
    } // 固定操作清单结束。
} // 审计应用服务结束。