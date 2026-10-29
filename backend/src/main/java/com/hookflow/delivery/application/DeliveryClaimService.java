package com.hookflow.delivery.application; // 任务领取编排属于投递应用层。

import com.hookflow.delivery.infrastructure.DeliveryAttemptRepository; // 在同一事务内插入 STARTED 尝试。
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository; // 定位候选、锁定任务并条件更新。
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository.LockedDueTask; // 使用持锁后读取的任务当前值。
import com.hookflow.endpoint.application.EndpointClaimGuard; // 通过接收端应用层先锁定并检查启用状态。
import com.hookflow.event.application.EventClaimGuard; // 通过事件应用层锁定、复查并同步事件。
import com.hookflow.event.application.EventClaimGuard.ClaimEvent; // 使用持锁后读取的事件当前值。
import java.time.Clock; // 领取时间来自项目统一注入的时钟。
import java.time.LocalDateTime; // 与 MySQL DATETIME(3) 对应。
import java.time.ZoneOffset; // 明确按 UTC 写入数据库。
import java.time.temporal.ChronoUnit; // 将时间截取到数据库支持的毫秒精度。
import java.util.Optional; // 候选已失效时明确返回没有领取结果。
import java.util.UUID; // 为本次领取和尝试生成独立的随机标识。
import org.springframework.context.annotation.Profile; // skeleton 配置组没有数据库，不创建此服务。
import org.springframework.stereotype.Service; // 注册为后续扫描入口可调用的应用服务。
import org.springframework.transaction.annotation.Isolation; // 明确使用 P0 约定的隔离级别。
import org.springframework.transaction.annotation.Transactional; // 使全部领取写入处于同一短事务。

@Profile("!skeleton") // 无数据库的工程骨架测试不加载领取服务。
@Service // 由 Spring 管理事务代理和依赖。
public class DeliveryClaimService { // 仅领取并持久化，不执行 HTTP 或处理 HTTP 结果。
    private final DueDeliveryTaskRepository tasks; // 保存任务模块自己的数据库入口。
    private final DeliveryAttemptRepository attempts; // 保存尝试记录数据库入口。
    private final EndpointClaimGuard endpoints; // 保存接收端模块的持锁检查入口。
    private final EventClaimGuard events; // 保存事件模块的持锁检查和状态同步入口。
    private final Clock clock; // 保存项目统一的可控 UTC 时钟。

    public DeliveryClaimService(DueDeliveryTaskRepository tasks, // 注入已有的任务仓库。
            DeliveryAttemptRepository attempts, // 注入已有的尝试仓库。
            EndpointClaimGuard endpoints, // 注入接收端应用层检查服务。
            EventClaimGuard events, Clock clock) { // 注入事件应用层服务和统一时钟。
        this.tasks = tasks; // 供非锁定定位和任务持锁更新使用。
        this.attempts = attempts; // 供同事务写入 STARTED 使用。
        this.endpoints = endpoints; // 保证第一个行锁是接收端。
        this.events = events; // 保证第二个行锁是事件并同步最新状态。
        this.clock = clock; // 避免用数据库 NOW() 或系统本地时区。
    } // 依赖初始化结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 一次候选领取只占用一个短 MySQL 写事务。
    public Optional<ClaimedTask> claim(long taskId) { // 接收非锁定扫描取得的候选任务 ID。
        if (taskId <= 0) { // 非法任务 ID 不对应可领取的数据库记录。
            return Optional.empty(); // 不对无效 ID 发起写入。
        } // 完成候选 ID 检查。
        Optional<Long> eventPk = tasks.findEventPk(taskId); // 普通读取外键；此时没有锁任务。
        if (eventPk.isEmpty()) { // 候选可能已被其他事务移除。
            return Optional.empty(); // 不把已消失的候选视为领取成功。
        } // 完成任务到事件的非锁定定位。
        Optional<Long> endpointId = events.findEndpointIdForClaim(eventPk.get()); // 普通读取事件关联的接收端。
        if (endpointId.isEmpty()) { // 事件不存在时没有可安全锁定的接收端。
            return Optional.empty(); // 跳过已失效的候选。
        } // 完成接收端 ID 定位。

        if (!endpoints.lockEnabled(endpointId.get())) { // 第一个行锁：接收端；复查当前是否启用。
            return Optional.empty(); // 停用或不存在时不增加次数、不创建尝试。
        } // 接收端锁由本领取事务持有至方法提交。
        Optional<ClaimEvent> lockedEvent = events.lockForClaim(eventPk.get(), endpointId.get()); // 第二个行锁：事件；复查接收端关联。
        if (lockedEvent.isEmpty()) { // 非锁定定位之后事件关联或记录可能已经变化。
            return Optional.empty(); // 不继续锁任务或写入。
        } // 事件锁由本领取事务持有至方法提交。
        Optional<LockedDueTask> lockedTask = tasks.lockCandidate(taskId); // 第三个行锁：任务；已被锁住时 SKIP LOCKED。
        if (lockedTask.isEmpty()) { // 任务不存在或当前正被别的事务锁定。
            return Optional.empty(); // 不创建本序号尝试。
        } // 后续检查只能依据实际锁定的数据库当前值。

        ClaimEvent event = lockedEvent.get(); // 取得已锁定事件的当前值。
        LockedDueTask task = lockedTask.get(); // 取得已锁定任务的当前值。
        LocalDateTime now = LocalDateTime.ofInstant( // 在本事务中只取一次领取时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); // 使用 UTC 毫秒精度。
        if (task.eventPk() != event.eventPk() // 复查任务仍关联刚才锁定的事件。
                || task.roundNo() != event.latestRoundNo() // 只允许领取事件最新一轮。
                || !task.status().equals(event.currentStatus()) // 事件冗余状态必须与当前任务状态一致。
                || (!"PENDING".equals(task.status()) && !"WAIT_RETRY".equals(task.status())) // 暂停、进行中和终态均不可领取。
                || task.nextRunAt() == null || task.nextRunAt().isAfter(now) // 未到期或缺少到期时间时不领取。
                || task.attemptCount() >= task.maxAttempts()) { // 次数耗尽时不能生成额外尝试。
            return Optional.empty(); // 任一复查失败均不修改任务、尝试或事件。
        } // 完成持锁状态、轮次、时间和次数的完整复查。

        String claimToken = UUID.randomUUID().toString(); // 为本次领取生成区别于其他尝试的令牌。
        String requestId = UUID.randomUUID().toString(); // 为后台尝试生成独立的日志关联 ID。
        LocalDateTime leaseUntil = now.plusSeconds(60) // 在请求超时之外至少预留一分钟缓冲。
                .plusNanos((long) task.timeoutMsSnapshot() * 1_000_000L); // 再覆盖任务快照的完整超时。
        int updated = tasks.markDelivering(task, now, claimToken, leaseUntil); // 条件更新任务状态、次数、令牌与租约。
        if (updated != 1) { // 即使已持锁，也不将条件更新失败当作领取成功。
            throw new IllegalStateException("任务领取状态更新失败"); // 抛异常回滚整个领取事务。
        } // 此时数据库更新尚未提交。
        int attemptNo = task.attemptCount() + 1; // 新尝试序号恰好等于已消耗次数加一。
        attempts.insertStarted(task.id(), attemptNo, now, requestId); // 同事务插入受唯一约束保护的 STARTED。
        if (!events.markDelivering(event)) { // 同事务同步最新轮事件的 DELIVERING 状态。
            throw new IllegalStateException("事件领取状态更新失败"); // 失败则连同任务和尝试一起回滚。
        } // 三处写入将由本方法的外层事务统一提交。
        return Optional.of(new ClaimedTask(task.id(), attemptNo, claimToken, leaseUntil)); // 返回领取标识；此处不发 HTTP。
    } // Spring 事务代理在方法成功返回后才提交数据库事务。

    public record ClaimedTask(long taskId, int attemptNo, // 标识已领取的任务和本轮尝试序号。
            String claimToken, LocalDateTime leaseUntil) { // 提供本次令牌与租约截止时间供后续阶段使用。
    } // 返回对象不含请求正文、签名密钥或 HTTP 执行行为。
} // 领取服务结束。