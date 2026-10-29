package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryResumeRepository;
import com.hookflow.event.application.EventClaimGuard;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryResumeService {
    private final DeliveryResumeRepository tasks;
    private final EventClaimGuard events;

    public DeliveryResumeService(DeliveryResumeRepository tasks, EventClaimGuard events) {
        this.tasks = tasks;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resumePaused(long endpointId, LocalDateTime now) {
        // 调用方先锁接收端；逐个锁事件再锁任务，不提前锁整批任务。
        for (var candidate : tasks.findCandidates(endpointId)) {
            var event = events.lockForClaim(candidate.eventPk(), endpointId)
                    .orElseThrow(() -> new IllegalStateException("恢复事件关联异常"));
            var task = tasks.lock(candidate.taskId())
                    .orElseThrow(() -> new IllegalStateException("恢复任务缺失"));
            if (task.eventPk() != event.eventPk()) {
                throw new IllegalStateException("恢复任务关联异常");
            }
            if (task.roundNo() != event.latestRoundNo() || !"PAUSED".equals(task.status())) {
                continue;
            }
            if (!"PAUSED".equals(event.currentStatus()) || task.resumeDueAt() == null
                    || !("PENDING".equals(task.resumeStatus()) || "WAIT_RETRY".equals(task.resumeStatus()))
                    || task.nextRunAt() != null || task.claimToken() != null || task.leaseUntil() != null) {
                throw new IllegalStateException("恢复任务状态不一致");
            }
            LocalDateTime dueAt = "PENDING".equals(task.resumeStatus())
                    || task.resumeDueAt().isBefore(now) ? now : task.resumeDueAt();
            if (tasks.resume(task, dueAt, now) != 1
                    || !events.markResumed(event, task.roundNo(), task.resumeStatus())) {
                throw new IllegalStateException("恢复任务事务写入失败");
            }
        }
    }
}
