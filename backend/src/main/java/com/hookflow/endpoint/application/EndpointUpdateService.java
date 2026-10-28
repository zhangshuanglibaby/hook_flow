package com.hookflow.endpoint.application; // 接收端编辑事务属于应用层。

import java.time.Clock; // 使用项目统一的可控时钟。
import java.time.LocalDateTime; // 对应数据库 DATETIME(3)。
import java.time.ZoneOffset; // 将时间明确转换为 UTC。
import java.time.temporal.ChronoUnit; // 将时间截取到毫秒精度。
import java.util.Optional; // 表示接收端可能不存在。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 配置。
import org.springframework.http.HttpStatus; // 为模拟器 URL 错误指定 400 状态。
import org.springframework.stereotype.Service; // 将编辑服务注册为 Spring 组件。
import org.springframework.transaction.annotation.Isolation; // 指定事务隔离级别。
import org.springframework.transaction.annotation.Transactional; // 保证配置和审计共同提交。
import org.springframework.web.server.ResponseStatusException; // 将非法的模拟器 URL 映射为请求错误。
import com.hookflow.audit.application.AuditLogService; // 使用现有审计服务。
import com.hookflow.audit.application.AuditLogService.Action; // 使用固定审计动作。
import com.hookflow.endpoint.api.ExternalUrlValidation; // 在服务边界再次执行相同的 URL 安全校验。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 返回不含密钥的配置。
import com.hookflow.endpoint.domain.EndpointTargetType; // 区分外部接收端和内部模拟器。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 使用参数化 JDBC 仓库。

@Profile("!skeleton") // 骨架配置不启动依赖数据库的编辑服务。
@Service // 供管理员控制器注入。
public class EndpointUpdateService { // 负责接收端配置更新及同事务成功审计。
    private final EndpointRepository endpoints; // 保存接收端仓库。
    private final Clock clock; // 保存统一时钟。
    private final AuditLogService auditLogs; // 保存审计服务。

    public EndpointUpdateService(EndpointRepository endpoints, Clock clock, AuditLogService auditLogs) { // 通过构造方法注入依赖。
        this.endpoints = endpoints; // 供事务内锁定、更新和读取配置。
        this.clock = clock; // 供事务内产生 UTC 修改时间。
        this.auditLogs = auditLogs; // 供事务内记录成功操作。
    } // 构造方法结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 保留现有名称及超时编辑的事务边界。
    public Optional<EndpointConfiguration> update(long id, String name, Integer timeoutMs, long actorUserId, String requestId) { // 兼容已有调用方。
        return updateInTransaction(id, name, null, timeoutMs, actorUserId, requestId); // 未提交 URL 时保留数据库中的原地址。
    } // 原有编辑入口结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // URL 编辑也必须与审计处于同一事务。
    public Optional<EndpointConfiguration> update(long id, String name, String url, Integer timeoutMs, long actorUserId, String requestId) { // 新增可编辑 URL 的入口。
        return updateInTransaction(id, name, url, timeoutMs, actorUserId, requestId); // 在已开启的事务内执行共同逻辑。
    } // URL 编辑入口结束。

    private Optional<EndpointConfiguration> updateInTransaction(long id, String name, String url, Integer timeoutMs, long actorUserId, String requestId) { // 仅由上述两个事务入口调用。
        if (name == null && url == null && timeoutMs == null) { // 至少需要一个待编辑字段。
            throw new IllegalArgumentException("接收端编辑字段缺失"); // 固定错误消息不回显请求内容。
        } // 空编辑检查结束。
        Optional<EndpointConfiguration> locked = endpoints.findByIdForUpdate(id); // 在当前事务内锁定目标接收端。
        if (locked.isEmpty()) { // 不存在时不更新，也不记录成功审计。
            return Optional.empty(); // 由控制器转换为 404。
        } // 不存在的情况处理结束。
        EndpointConfiguration before = locked.orElseThrow(); // 取得已锁定的原配置。
        if (url != null && before.targetType() != EndpointTargetType.EXTERNAL) { // 模拟器不能接受外部 URL。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝请求并回滚事务。
        } // 目标类型检查结束。
        String updatedUrl = url == null ? before.url() : ExternalUrlValidation.validate(url); // 只对新提交的 EXTERNAL URL 再次执行安全校验。
        String updatedName = name == null ? before.name() : name; // 未提交名称时保留原值。
        int updatedTimeoutMs = timeoutMs == null ? before.timeoutMs() : timeoutMs; // 未提交超时时保留原值。
        LocalDateTime now = LocalDateTime.ofInstant( // 构造配置和审计共用的数据库时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 与数据库毫秒精度一致。
                ZoneOffset.UTC // 不依赖服务器本地时区。
        ); // 本次事务时间确定。
        int changed = endpoints.updateConfiguration(id, updatedName, updatedUrl, updatedTimeoutMs, now); // 使用参数化 SQL 更新配置。
        if (changed != 1) { // 行锁下应恰好更新一条记录。
            throw new IllegalStateException("接收端更新数量异常"); // 异常时回滚配置及审计。
        } // 更新数量检查结束。
        EndpointConfiguration updated = endpoints.findById(id) // 在同一事务内读取实际保存的非敏感配置。
                .orElseThrow(() -> new IllegalStateException("接收端更新后不可见")); // 读取异常时回滚。
        auditLogs.recordSuccess(actorUserId, Action.ENDPOINT_UPDATED, id, now, requestId); // 审计失败也回滚本次配置更新。
        return Optional.of(updated); // 只返回不含签名密钥的配置。
    } // 共同的事务内更新逻辑结束。
} // 接收端编辑服务结束。