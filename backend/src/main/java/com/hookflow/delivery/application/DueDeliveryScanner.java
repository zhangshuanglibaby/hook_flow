package com.hookflow.delivery.application; // 到期扫描是投递应用层的后台入口。

import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository; // 使用已有的非锁定、小批量候选查询。
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository.DueCandidate; // 保存候选的任务 ID 和排序键。
import java.util.List; // 接收一次最多 16 条的候选列表。
import java.time.Clock; // 扫描时间与领取服务使用同一个可注入时钟。
import java.time.LocalDateTime; // 对应 MySQL DATETIME(3) 的 UTC 时间。
import java.time.ZoneOffset; // 明确将时钟瞬间转换为 UTC。
import java.time.temporal.ChronoUnit; // 将查询时间截取到毫秒精度。
import org.springframework.context.annotation.Configuration; // 单独配置正式环境的定时调度。
import org.springframework.context.annotation.Profile; // 避免无数据库或现有测试配置组自动扫描。
import org.springframework.scheduling.annotation.EnableScheduling; // 启用 Spring 的定时方法处理。
import org.springframework.scheduling.annotation.Scheduled; // 约每秒触发一次数据库扫描。
import org.springframework.stereotype.Service; // 将可直接调用的扫描入口注册为应用服务。
import org.springframework.transaction.support.TransactionSynchronizationManager; // 禁止在调用方事务内派发 HTTP。

@Profile("!skeleton") // 无数据库的骨架配置组不创建扫描服务。
@Service // 由 Spring 注入候选仓库、领取服务和统一时钟。
public class DueDeliveryScanner { // 扫描并领取后，将投递工作交给有界执行器。
    private final DueDeliveryTaskRepository tasks; // 保存非锁定读取候选任务的仓库。
    private final DeliveryClaimService claims; // 保存每个候选各自使用短事务的领取服务。
    private final Clock clock; // 保存与领取服务一致的可控时钟。
    private final DeliveryExecutionService executions; // 领取提交后才在有界线程池运行 HTTP。
    private final BoundedDeliveryExecutor executor; // 领取前预留容量，避免队列满后遗留任务。
    private LocalDateTime lastDueAt; // 记住本轮扫描处理到的到期时间；到末尾时重置。
    private long lastTaskId; // 与到期时间一起组成稳定排序游标。

    public DueDeliveryScanner(DueDeliveryTaskRepository tasks, // 注入到期候选查询。
            DeliveryClaimService claims, Clock clock, DeliveryExecutionService executions,
            BoundedDeliveryExecutor executor) { // 注入领取后执行入口与有界容量。
        this.tasks = tasks; // 扫描时仅从数据库读取小批量 ID。
        this.claims = claims; // 每个 ID 都交给事务服务重新锁定和复查。
        this.clock = clock; // 不使用数据库 NOW() 或本地时区。
        this.executions = executions; // 单次 HTTP 和完成写回在工作线程执行。
        this.executor = executor; // 满载时不领取，不依赖尚未实现的恢复器。
    } // 构造方法结束。

    @Scheduled(initialDelay = 1000, fixedDelay = 1000) // 启动约一秒后扫描，此后每次完成约一秒再扫描。
    public synchronized void scanDueTasks() { // 串行维护游标，避免并发触发使扫描位置互相覆盖。
        if (TransactionSynchronizationManager.isActualTransactionActive()) { // 外部事务可能尚未提交领取。
            throw new IllegalStateException("不能在数据库事务中扫描并派发 HTTP"); // 保证领取提交后才执行请求。
        }
        LocalDateTime now = LocalDateTime.ofInstant( // 每次扫描只读取一次当前时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); // 使用 UTC 毫秒精度。
        List<DueCandidate> candidates = tasks.findDueCandidatesAfter( // 普通查询只取一小批候选，不锁任务。
                now, lastDueAt, lastTaskId); // 从上一次处理的位置继续。
        if (candidates.isEmpty() && lastDueAt != null) { // 已到当前排序范围末尾。
            lastDueAt = null; // 回到最早的到期任务。
            lastTaskId = 0; // 清除旧游标的任务 ID。
            candidates = tasks.findDueCandidatesAfter(now, null, 0); // 本次仍最多处理 16 条候选。
        } // 完成游标回绕。
        for (DueCandidate candidate : candidates) { // 每批最多处理十六个候选。
            var available = executor.reserve(); // 领取之前取得容量；满载时让未领取任务留在数据库。
            if (available.isEmpty()) {
                break; // 当前周期不再领取，下一周期仍从这条候选重试。
            }
            try (var reservation = available.get()) {
                var claim = claims.claim(candidate.id()); // Spring 事务代理返回时领取已提交。
                claim.ifPresent(value -> reservation.submit(() -> executions.execute(value)));
            }
            lastDueAt = candidate.dueAt(); // 只推进实际处理过的候选。
            lastTaskId = candidate.id();
        }
        if (candidates.size() < 16 && (candidates.isEmpty()
                || lastTaskId == candidates.getLast().id())) { // 读到末尾且已处理完整批次时回绕。
            lastDueAt = null;
            lastTaskId = 0;
        } // 容量满时保留已处理位置，不跳过未领取任务。
    } // 方法返回后由定时器等待下一次扫描。
} // 扫描服务结束。

@Profile("!skeleton & !p2-login-test") // 正式环境开启调度；现有数据库测试配置组不自动领取夹具。
@Configuration(proxyBeanMethods = false) // 此类仅用于注册调度能力，不创建额外业务对象。
@EnableScheduling // 使 Spring 识别上面的 @Scheduled 方法。
class DeliveryClaimSchedulingConfiguration { // 与扫描器放在同一文件，避免改动工程入口。
} // 调度开关配置结束。