package com.hookflow.endpoint.application; // 接收端创建时的密钥发放和事务协调属于应用层。

import java.time.Clock; // 使用项目统一注入的 UTC 时钟。
import java.time.LocalDateTime; // 对应数据库 DATETIME(3) 的写入类型。
import java.time.ZoneOffset; // 明确按 UTC 转换数据库时间。
import java.time.temporal.ChronoUnit; // 将事务时间截取到毫秒精度。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 模式。
import org.springframework.stereotype.Service; // 注册内部应用服务。
import org.springframework.transaction.annotation.Isolation; // 使用既有写事务隔离约定。
import org.springframework.transaction.annotation.Transactional; // 将接收端插入和审计放在同一事务。
import com.hookflow.audit.application.AuditLogService; // 调用已有的成功操作审计服务。
import com.hookflow.audit.application.AuditLogService.Action; // 使用固定的接收端创建操作类型。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 仅返回不含密钥的接收端配置。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 用现有安全查询读取创建结果。
import com.hookflow.endpoint.infrastructure.EndpointSecretRepository; // 写入接收端签名密钥的密文。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 生成独立密钥并加密。

@Profile("!skeleton") // 无数据库的骨架配置不创建此服务。
@Service // 供后续安全的内部创建流程注入，不自动开放 HTTP 接口。
public class EndpointSecretProvisioningService { // 当前仅提供固定模拟器目标的密钥入库用例。
    private final EndpointRepository endpoints; // 只用于查询不含密钥的创建结果。
    private final EndpointSecretRepository secrets; // 只用于存储接收端密文。
    private final EndpointSigningSecretCipher cipher; // 负责安全生成和加密签名密钥。
    private final AuditLogService auditLogs; // 负责同事务成功审计。
    private final Clock clock; // 提供本次事务唯一的 UTC 时间。

    public EndpointSecretProvisioningService(EndpointRepository endpoints, EndpointSecretRepository secrets,
            EndpointSigningSecretCipher cipher, AuditLogService auditLogs, Clock clock) { // 注入已有组件和新密文组件。
        this.endpoints = endpoints; // 保存不读取密文的配置仓库。
        this.secrets = secrets; // 保存密文仓库。
        this.cipher = cipher; // 保存加密组件。
        this.auditLogs = auditLogs; // 保存审计服务。
        this.clock = clock; // 保存项目统一时钟。
    } // 应用服务构造结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 插入接收端和成功审计共同提交或回滚。
    public EndpointConfiguration createSimulator(String name, int timeoutMs, long actorUserId, String requestId) { // 仅供内部调用，不接收 URL。
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) { // 拒绝空白或超长名称。
            throw new IllegalArgumentException("接收端名称不合法"); // 不回显调用方提供的名称。
        } // 名称检查结束。
        if (timeoutMs < 1 || timeoutMs > 15000) { // 遵守接收端表和接口约定的超时范围。
            throw new IllegalArgumentException("接收端超时不合法"); // 不插入越界配置。
        } // 超时检查结束。
        LocalDateTime now = LocalDateTime.ofInstant( // 建立创建和审计共用的数据库时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 对齐 MySQL DATETIME(3) 的毫秒精度。
                ZoneOffset.UTC // 避免受机器本地时区影响。
        ); // 本次事务时间取得完毕。
        String signingSecret = cipher.generateSecret(); // 为此接收端单独生成随机签名密钥。
        byte[] ciphertext = cipher.encrypt(signingSecret); // 立即用环境主密钥加密，不写入明文。
        long id = secrets.insertSimulator(name, timeoutMs, ciphertext, now); // 固定目标为 SIMULATOR、URL 为 NULL。
        EndpointConfiguration created = endpoints.findById(id) // 在同一事务读取不含密文的实际保存结果。
                .orElseThrow(() -> new IllegalStateException("接收端创建后不可见")); // 读取异常时回滚插入。
        auditLogs.recordSuccess(actorUserId, Action.ENDPOINT_CREATED, id, now, requestId); // 审计失败则接收端插入一同回滚。
        return created; // 模拟器密钥永不通过创建结果对外返回。
    } // 内部模拟器创建事务结束。
} // 接收端密钥发放应用服务结束。