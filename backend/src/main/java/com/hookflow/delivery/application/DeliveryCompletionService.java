package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryAttemptRepository;
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult;
import com.hookflow.endpoint.application.EndpointClaimGuard;
import com.hookflow.event.application.EventClaimGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryCompletionService { // 仅负责短事务结果写回，不执行网络请求。
    private final DueDeliveryTaskRepository tasks;
    private final DeliveryAttemptRepository attempts;
    private final EndpointClaimGuard endpoints;
    private final EventClaimGuard events;
    private final Clock clock;

    public DeliveryCompletionService(DueDeliveryTaskRepository tasks, DeliveryAttemptRepository attempts,
            EndpointClaimGuard endpoints, EventClaimGuard events, Clock clock) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.endpoints = endpoints;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean complete(long taskId, int attemptNo, String token, SingleDeliveryHttpResult result) {
        if (taskId <= 0 || attemptNo < 1 || attemptNo > 3 || token == null || result == null
                || result.classification() == null) {
            return false;
        }
        var eventPk = tasks.findEventPk(taskId); // 非锁定定位外键，随后按固定顺序重新核对。
        if (eventPk.isEmpty()) {
            return false;
        }
        var endpointId = events.findEndpointIdForClaim(eventPk.get());
        if (endpointId.isEmpty()) {
            return false;
        }
        boolean enabled = endpoints.lockEnabled(endpointId.get()); // 第一把行锁；停用时仍允许本次结束。
        var event = events.lockForClaim(eventPk.get(), endpointId.get()); // 第二把行锁。
        if (event.isEmpty()) {
            return false;
        }
        var task = tasks.lockForCompletion(taskId); // 第三把行锁。
        if (task.isEmpty()) {
            return false;
        }
        var current = task.get();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ZoneOffset.UTC); // 同一写事务只取一次 UTC 毫秒时间。
        if (current.eventPk() != eventPk.get() || current.roundNo() != event.get().latestRoundNo()
                || !"DELIVERING".equals(event.get().currentStatus())
                || !"DELIVERING".equals(current.status()) || current.attemptCount() != attemptNo
                || !token.equals(current.claimToken()) || current.leaseUntil() == null
                || !current.leaseUntil().isAfter(now)) {
            return false; // 旧令牌、旧轮或过期租约不能触碰尝试行。
        }
        var started = attempts.lockStarted(taskId, attemptNo); // 最后锁定对应的 STARTED 尝试。
        if (started.isEmpty()) {
            return false;
        }
        LocalDateTime endedAt = now;
        if (endedAt.isBefore(started.get().startedAt())) {
            throw new IllegalStateException("完成时间早于领取时间"); // 时钟倒退时回滚，不能改写事务本次的“现在”。
        }
        long duration = ChronoUnit.MILLIS.between(started.get().startedAt(), endedAt);
        if (duration < 0) {
            throw new IllegalStateException("投递耗时无效");
        }
        boolean retry = result.classification().retryable() && attemptNo < current.maxAttempts();
        String next = result.classification().result() == AttemptResult.SUCCESS
                ? "SUCCESS" : retry ? (enabled ? "WAIT_RETRY" : "PAUSED") : "FINAL_FAILED";
        LocalDateTime due = null;
        if (retry) {
            BigDecimal seconds = attemptNo == 1 ? current.retryDelay1Seconds()
                    : current.retryDelay2Seconds();
            long nanos = seconds.movePointRight(9).longValueExact();
            long millis = Math.addExact(nanos, 999_999L) / 1_000_000L;
            due = endedAt.plus(millis, ChronoUnit.MILLIS); // 毫秒存储向上取整，不早于快照间隔。
        }
        if (attempts.finish(started.get(), result, endedAt, duration) != 1
                || tasks.markCompleted(current, token, next, due, endedAt) != 1
                || !events.markDeliveryCompleted(event.get(), current.roundNo(), next, endedAt)) {
            throw new IllegalStateException("投递结果事务写入失败"); // 三表任意失败则整体回滚。
        }
        return true;
    }
}
