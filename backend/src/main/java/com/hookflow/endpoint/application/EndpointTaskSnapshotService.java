package com.hookflow.endpoint.application; // 接收端快照的事务入口属于接收端应用层。

import java.util.Optional; // 接收端不存在时返回空结果。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 模式。
import org.springframework.stereotype.Service; // 将快照入口注册为应用服务。
import org.springframework.transaction.annotation.Propagation; // 要求加入调用方已有事务。
import org.springframework.transaction.annotation.Transactional; // 声明锁定和读取必须处于同一事务。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 读取已锁定的非敏感接收端配置。
import com.hookflow.endpoint.domain.EndpointTaskSnapshot; // 返回可供将来任务创建使用的配置快照。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 先锁定接收端行。
import com.hookflow.endpoint.infrastructure.EndpointSecretRepository; // 在持锁期间读取签名密钥密文。

@Profile("!skeleton") // 骨架配置不创建依赖数据库的快照服务。
@Service // 后续事件模块只能通过此应用层入口取得接收端快照。
public class EndpointTaskSnapshotService { // 协调接收端行锁、启用状态复查和密文复制。
    private final EndpointRepository endpoints; // 保存不读取密文的接收端仓库。
    private final EndpointSecretRepository secrets; // 保存只读取二进制密文的仓库。

    public EndpointTaskSnapshotService(EndpointRepository endpoints, EndpointSecretRepository secrets) { // 注入两个现有仓库。
        this.endpoints = endpoints; // 供事务内先锁定接收端。
        this.secrets = secrets; // 供持锁后读取同一接收端的密文。
    } // 快照服务构造结束。

    @Transactional(propagation = Propagation.MANDATORY) // 必须加入调用方已有的 READ COMMITTED 写事务，不能自行开启事务。
    public Optional<EndpointTaskSnapshot> lockEnabledSnapshot(long endpointId) { // 先锁行并复查启用，再形成本次配置快照。
        Optional<EndpointConfiguration> locked = endpoints.findByIdForUpdate(endpointId); // 按 P0-03 锁顺序首先锁定 endpoint。
        if (locked.isEmpty()) { // 数据库没有指定 ID 的接收端。
            return Optional.empty(); // 交由未来调用方区分并处理不存在的情况。
        } // 不存在情况处理结束。
        EndpointConfiguration configuration = locked.orElseThrow(); // 取得已被当前事务锁定的配置。
        if (!configuration.enabled()) { // 在锁保护下复查最新启用状态。
            throw new EndpointDisabledException(); // 阻止为停用的接收端形成新任务快照。
        } // 启用状态检查结束。
        byte[] ciphertext = secrets.findCiphertextForSnapshot(endpointId) // 持锁读取与配置同一行的密文字节。
                .orElseThrow(() -> new IllegalStateException("接收端签名密钥密文缺失")); // 数据异常时明确失败，不使用明文替代。
        return Optional.of(new EndpointTaskSnapshot( // 构造独立对象，供调用方将来写入任务快照列。
                configuration.targetType(), // 复制锁定时的目标类型。
                configuration.url(), // 复制锁定时的 URL；模拟器为 null。
                configuration.timeoutMs(), // 复制锁定时的超时毫秒数。
                ciphertext // 快照对象会复制数组，数据库密文不会被调用方修改。
        )); // 此处既不写 delivery_task，也不解密签名密钥。
    } // 已启用接收端的快照读取结束。

    public static final class EndpointDisabledException extends RuntimeException { // 区分停用与不存在，供未来接口映射为约定的 422。
        public EndpointDisabledException() { // 构造不包含接收端配置或密钥的异常。
            super("接收端已停用"); // 使用固定消息，不回显敏感数据。
        } // 停用异常构造结束。
    } // 接收端停用异常定义结束。
} // 接收端任务快照服务结束。