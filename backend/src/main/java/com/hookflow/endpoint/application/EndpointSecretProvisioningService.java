package com.hookflow.endpoint.application; // 接收端创建时的密钥发放和事务协调属于应用层。

import java.time.Clock; // 使用项目统一注入的 UTC 时钟。
import java.time.LocalDateTime; // 对应数据库 DATETIME(3) 的写入类型。
import java.time.ZoneOffset; // 明确按 UTC 转换数据库时间。
import java.time.temporal.ChronoUnit; // 将事务时间截取到毫秒精度。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 模式。
import org.springframework.stereotype.Service; // 注册内部应用服务。
import org.springframework.transaction.annotation.Isolation; // 使用既有写事务隔离约定。
import org.springframework.transaction.annotation.Transactional; // 将接收端插入和审计放在同一事务。
import com.hookflow.endpoint.api.ExternalUrlValidation; // 创建服务自身复查外部 URL，不能只依赖未来的控制器校验。
import com.hookflow.audit.application.AuditLogService; // 调用已有的成功操作审计服务。
import com.hookflow.audit.application.AuditLogService.Action; // 使用固定的接收端创建操作类型。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 仅返回不含密钥的接收端配置。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 用现有安全查询读取创建结果。
import com.hookflow.endpoint.infrastructure.EndpointSecretRepository; // 写入接收端签名密钥的密文。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 生成独立密钥并加密。

@Profile("!skeleton") // 无数据库的骨架配置不创建此服务。
@Service // 供后续安全的内部创建流程注入，不自动开放 HTTP 接口。
public class EndpointSecretProvisioningService { // 提供模拟器及外部接收端的安全创建事务。
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

    @Transactional(isolation = Isolation.READ_COMMITTED) // 旧内部调用仍开启创建和审计的共同事务。
    public EndpointConfiguration createSimulator(String name, int timeoutMs, long actorUserId, String requestId) { // 保留已有四参数调用方式。
        return createSimulator(name, timeoutMs, true, actorUserId, requestId); // 旧调用默认启用模拟器。
    } // 默认启用的内部创建入口结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 管理员创建时，插入和审计共同提交或回滚。
    public EndpointConfiguration createSimulator(String name, int timeoutMs, boolean enabled, long actorUserId, String requestId) { // 模拟器不接收用户 URL。
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) { // 服务边界拒绝空白或超长名称。
            throw new IllegalArgumentException("接收端名称不合法"); // 错误文字不回显名称。
        } // 名称检查结束。
        if (timeoutMs < 1 || timeoutMs > 15000) { // 超时必须符合接口和数据库范围。
            throw new IllegalArgumentException("接收端超时不合法"); // 越界时不保存配置。
        } // 超时检查结束。
        LocalDateTime now = LocalDateTime.ofInstant( // 创建行和审计行共用同一个时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 对齐数据库毫秒精度。
                ZoneOffset.UTC // 明确使用 UTC 时区。
        ); // 本次创建时间确定。
        String signingSecret = cipher.generateSecret(); // 为该模拟器单独生成随机密钥。
        byte[] ciphertext = cipher.encrypt(signingSecret); // 数据库只接收加密后的密文。
        long id = secrets.insertSimulator(name, timeoutMs, enabled, ciphertext, now); // 固定 SIMULATOR、空 URL，并保存启用状态。
        EndpointConfiguration created = endpoints.findById(id) // 在事务中读取不含密钥的配置。
                .orElseThrow(() -> new IllegalStateException("接收端创建后不可见")); // 查询失败时回滚。
        auditLogs.recordSuccess(actorUserId, Action.ENDPOINT_CREATED, id, now, requestId); // 审计失败时插入一同回滚。
        return created; // 模拟器创建结果绝不交付明文密钥。
    } // 模拟器创建事务结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 外部接收端插入和审计必须共同提交或回滚。
    public CreatedExternalEndpoint createExternal(String name, String url, int timeoutMs, boolean enabled, long actorUserId, String requestId) { // 接收外部目标创建参数。
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) { // 服务边界拒绝空白或超长名称。
            throw new IllegalArgumentException("接收端名称不合法"); // 错误文字不回显名称。
        } // 名称检查结束。
        if (timeoutMs < 1 || timeoutMs > 15000) { // 超时必须符合接口和数据库范围。
            throw new IllegalArgumentException("接收端超时不合法"); // 越界时不保存配置。
        } // 超时检查结束。
        String validatedUrl = ExternalUrlValidation.validate(url); // 在任何数据库写入前校验外部 HTTPS 目标。
        LocalDateTime now = LocalDateTime.ofInstant( // 创建行和审计行共用同一个时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 对齐数据库毫秒精度。
                ZoneOffset.UTC // 明确使用 UTC 时区。
        ); // 本次创建时间确定。
        String signingSecret = cipher.generateSecret(); // 为该接收端单独生成随机签名密钥。
        byte[] ciphertext = cipher.encrypt(signingSecret); // 入库前用环境主密钥加密。
        long id = secrets.insertExternal(name, validatedUrl, timeoutMs, enabled, ciphertext, now); // 只保存已校验 URL 和密文。
        EndpointConfiguration created = endpoints.findById(id) // 在事务中读取不含密钥的配置。
                .orElseThrow(() -> new IllegalStateException("接收端创建后不可见")); // 查询失败时回滚。
        auditLogs.recordSuccess(actorUserId, Action.ENDPOINT_CREATED, id, now, requestId); // 审计失败时插入一同回滚。
        return new CreatedExternalEndpoint(created, signingSecret); // 仅本次创建调用取得明文密钥。
    } // 外部接收端创建事务结束。


    public record CreatedExternalEndpoint(EndpointConfiguration configuration, String signingSecret) { // 临时承载本次创建结果和一次性密钥。
        @Override // 避免 record 默认的 toString 意外打印明文密钥。
        public String toString() { // 即使误将结果转成文本，也不展示密钥或完整 URL。
            return "CreatedExternalEndpoint[REDACTED]"; // 只返回固定脱敏标记。
        } // 安全字符串表示结束。
    } // 一次性创建结果定义结束。
} // 接收端密钥发放应用服务结束。