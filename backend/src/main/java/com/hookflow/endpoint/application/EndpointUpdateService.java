package com.hookflow.endpoint.application; // 接收端编辑用例属于应用层。

import java.time.Clock; // 使用项目统一注入的可控 UTC 时钟。
import java.time.LocalDateTime; // 对应数据库的 DATETIME(3) 字段。
import java.time.ZoneOffset; // 明确将时间转换为 UTC。
import java.time.temporal.ChronoUnit; // 将时间截取到毫秒精度。
import java.util.Optional; // 表示指定接收端可能不存在。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 配置。
import org.springframework.stereotype.Service; // 将编辑用例注册为 Spring 服务。
import org.springframework.transaction.annotation.Isolation; // 指定事务隔离级别。
import org.springframework.transaction.annotation.Transactional; // 保证编辑和审计共同提交或回滚。
import com.hookflow.audit.application.AuditLogService; // 调用现有成功操作审计服务。
import com.hookflow.audit.application.AuditLogService.Action; // 使用固定的审计操作名称。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 返回不含签名密钥的配置。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 调用接收端 JDBC 仓库。

@Profile("!skeleton") // 无数据库的骨架配置不创建此服务。
@Service // 供后续管理员接口注入。
public class EndpointUpdateService { // 仅处理当前可独立完成的名称和超时编辑。
    private final EndpointRepository endpoints; // 保存接收端仓库。
    private final Clock clock; // 保存项目统一时钟。
    private final AuditLogService auditLogs; // 保存成功操作审计服务。

    public EndpointUpdateService(EndpointRepository endpoints, Clock clock, AuditLogService auditLogs) { // 通过构造方法注入依赖。
        this.endpoints = endpoints; // 保存仓库供事务内查询和更新。
        this.clock = clock; // 保存时钟供事务内取得 UTC 时间。
        this.auditLogs = auditLogs; // 保存审计服务供成功编辑时调用。
    } // 构造方法结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 行锁、字段更新和审计处于同一个写事务。
    public Optional<EndpointConfiguration> update(long id, String name, Integer timeoutMs, long actorUserId, String requestId) { // 接收已校验字段、管理员内部 ID 和服务端请求 ID。
        if (name == null && timeoutMs == null) { // 至少需要一个待编辑字段。
            throw new IllegalArgumentException("接收端编辑字段缺失"); // 固定错误消息不回显请求内容。
        } // 空编辑检查结束。
        Optional<EndpointConfiguration> locked = endpoints.findByIdForUpdate(id); // 在当前事务中锁定指定接收端。
        if (locked.isEmpty()) { // 不存在时不更新数据，也不记录成功审计。
            return Optional.empty(); // 由后续接口层转换为 404。
        } // 不存在的情况处理结束。
        EndpointConfiguration before = locked.orElseThrow(); // 取得已确认存在且被锁定的原配置。
        LocalDateTime now = LocalDateTime.ofInstant( // 构造本次编辑和审计共用的数据库时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 与 DATETIME(3) 的毫秒精度保持一致。
                ZoneOffset.UTC // 不使用服务器本地时区。
        ); // 本事务的 UTC 时间取得完毕。
        String updatedName = name == null ? before.name() : name; // 未提交名称时保留原值。
        int updatedTimeoutMs = timeoutMs == null ? before.timeoutMs() : timeoutMs; // 未提交超时时保留原值。
        endpoints.updateNameAndTimeout(id, updatedName, updatedTimeoutMs, now); // 只修改名称、超时和修改时间。
        EndpointConfiguration updated = endpoints.findById(id) // 在同一事务中读取数据库实际保存的非敏感配置。
                .orElseThrow(() -> new IllegalStateException("接收端更新后不可见")); // 异常时让编辑事务整体回滚。
        auditLogs.recordSuccess(actorUserId, Action.ENDPOINT_UPDATED, id, now, requestId); // 审计失败时连同配置更新一起回滚。
        return Optional.of(updated); // 只向调用方返回不含密钥的配置。
    } // 编辑事务结束。
} // 接收端编辑应用服务结束。