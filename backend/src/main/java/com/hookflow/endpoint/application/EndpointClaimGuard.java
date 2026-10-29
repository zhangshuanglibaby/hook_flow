package com.hookflow.endpoint.application; // 接收端领取前的检查属于接收端应用层。

import com.hookflow.endpoint.infrastructure.EndpointRepository; // 复用现有接收端行锁查询。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 配置。
import org.springframework.stereotype.Service; // 注册为可由投递应用层调用的服务。
import org.springframework.transaction.annotation.Propagation; // 指定必须加入已有事务。
import org.springframework.transaction.annotation.Transactional; // 保证行锁位于调用方事务内。

@Profile("!skeleton") // 骨架测试不加载数据库相关服务。
@Service // 后续领取用例通过应用层调用，不直接访问接收端仓储。
public class EndpointClaimGuard { // 只负责领取前锁定接收端并检查启用状态。
    private final EndpointRepository endpoints; // 保存接收端模块自己的仓储入口。

    public EndpointClaimGuard(EndpointRepository endpoints) { // 由 Spring 注入现有仓储。
        this.endpoints = endpoints; // 供持锁检查使用。
    }

    @Transactional(propagation = Propagation.MANDATORY) // 禁止在领取事务之外单独取锁并立即释放。
    public boolean lockEnabled(long endpointId) { // 调用方须先非锁定定位这个接收端 ID。
        return endpoints.findByIdForUpdate(endpointId) // 以主键锁定接收端行并读取当前状态。
                .map(configuration -> configuration.enabled()) // 仅当前仍启用时允许继续。
                .orElse(false); // 接收端不存在时不领取任务。
    }
}