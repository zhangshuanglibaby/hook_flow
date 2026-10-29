package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryPauseRepository;
import com.hookflow.event.application.EventClaimGuard;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryPauseService {
    private final DeliveryPauseRepository tasks;
    private final EventClaimGuard events;

    public DeliveryPauseService(DeliveryPauseRepository tasks, EventClaimGuard events) {
        this.tasks = tasks;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void pauseUnsent(long endpointId, LocalDateTime now) {
        // 调用方必须先锁接收端；逐个锁事件再锁任务，不提前锁整批任务。
        for (var candidate : tasks.findCandidates(endpointId)) {
            var event = events.lockForClaim(candidate.eventPk(), endpointId)
                    .orElseThrow(() -> new IllegalStateException("暂停事件关联异常"));
            var task = tasks.lock(candidate.taskId())
                    .orElseThrow(() -> new IllegalStateException("暂停任务缺失"));
            if (task.eventPk() != event.eventPk()) {
                throw new IllegalStateException("暂停任务关联异常");
            }
            if (task.roundNo() != event.latestRoundNo()
                    || !("PENDING".equals(task.status()) || "WAIT_RETRY".equals(task.status()))) {
                continue;
            }
            if (!task.status().equals(event.currentStatus()) || task.dueAt() == null) {
                throw new IllegalStateException("暂停任务状态不一致");
            }
            if (tasks.pause(task, now) != 1
                    || !events.markPaused(event, task.roundNo(), task.status())) {
                throw new IllegalStateException("暂停任务事务写入失败");
            }
        }
    }
}
