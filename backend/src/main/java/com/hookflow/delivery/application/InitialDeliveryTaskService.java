package com.hookflow.delivery.application; // 首轮任务创建入口属于投递应用层。

import com.hookflow.delivery.infrastructure.InitialDeliveryTaskRepository; // 调用本模块的任务仓库。
import com.hookflow.endpoint.domain.EndpointTaskSnapshot; // 接收已锁定接收端的配置快照。
import java.time.LocalDateTime; // 接收本次事务统一的 UTC 数据库时间。
import org.springframework.context.annotation.Profile; // skeleton 模式不加载数据库服务。
import org.springframework.stereotype.Service; // 将应用层入口注册为 Spring 服务。
import org.springframework.transaction.annotation.Propagation; // 指定必须加入已有事务。
import org.springframework.transaction.annotation.Transactional; // 保证创建调用处于事务内。

@Profile("!skeleton") // 仅在正式数据库模式下启用。
@Service // 供事件创建服务跨模块调用。
public class InitialDeliveryTaskService { // 只负责创建新事件的第 1 轮任务。
    private final InitialDeliveryTaskRepository tasks; // 保存本模块的数据库写入入口。
    private final DeliveryTaskPolicy policy; // 保存启动时读取的投递策略。

    public InitialDeliveryTaskService(InitialDeliveryTaskRepository tasks,
            DeliveryTaskPolicy policy) { // 由 Spring 注入仓库和策略。
        this.tasks = tasks; // 后续写入任务表使用。
        this.policy = policy; // 后续复制次数和两档延迟使用。
    }

    @Transactional(propagation = Propagation.MANDATORY) // 必须加入事件创建事务，禁止自行单独提交任务。
    public void createFirstRound(long eventPk, EndpointTaskSnapshot snapshot,
            LocalDateTime createdAt) { // 接收新事件主键、接收端快照和事务统一时间。
        tasks.insert(eventPk, snapshot, policy.maxAttempts(), // 写入第 1 轮及最大尝试次数。
                policy.retryDelay1Seconds(), // 将第一档重试秒数复制到任务行。
                policy.retryDelay2Seconds(), // 将第二档重试秒数复制到任务行。
                createdAt); // 创建和首次到期时间使用同一个 UTC 时间。
    }
}