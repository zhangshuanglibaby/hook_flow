package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryAttemptRepository;
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository;
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository.LockedDeliveryTask;
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
public class DeliveryRecoveryService {
    private final DueDeliveryTaskRepository tasks;
    private final DeliveryAttemptRepository attempts;
    private final EndpointClaimGuard endpoints;
    private final EventClaimGuard events;
    private final Clock clock;

    public DeliveryRecoveryService(DueDeliveryTaskRepository tasks,
            DeliveryAttemptRepository attempts, EndpointClaimGuard endpoints,
            EventClaimGuard events, Clock clock) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.endpoints = endpoints;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean recover(long taskId, String expectedToken) {
        if (taskId <= 0 || expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        var eventPk = tasks.findEventPk(taskId);
        if (eventPk.isEmpty()) {
            return false;
        }
        var endpointId = events.findEndpointIdForClaim(eventPk.get());
        if (endpointId.isEmpty()) {
            return false;
        }
        boolean enabled = endpoints.lockEnabled(endpointId.get());
        var event = events.lockForClaim(eventPk.get(), endpointId.get());
        if (event.isEmpty()) {
            return false;
        }
        var locked = tasks.lockForCompletion(taskId);
        if (locked.isEmpty()) {
            return false;
        }
        LockedDeliveryTask task = locked.get();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ZoneOffset.UTC);
        if (task.eventPk() != eventPk.get()
                || task.roundNo() != event.get().latestRoundNo()
                || !"DELIVERING".equals(task.status())
                || !"DELIVERING".equals(event.get().currentStatus())
                || task.leaseUntil() == null || task.leaseUntil().isAfter(now)
                || task.claimToken() == null || task.claimToken().isBlank()
                || !expectedToken.equals(task.claimToken())
                || task.attemptCount() < 1 || task.attemptCount() > task.maxAttempts()) {
            return false;
        }
        var started = attempts.lockStarted(taskId, task.attemptCount());
        if (started.isEmpty()) {
            return false;
        }
        long duration = ChronoUnit.MILLIS.between(started.get().startedAt(), now);
        if (duration < 0) {
            throw new IllegalStateException("恢复时间早于尝试开始时间");
        }
        boolean retry = task.attemptCount() < task.maxAttempts();
        String next = retry ? (enabled ? "WAIT_RETRY" : "PAUSED") : "FINAL_FAILED";
        LocalDateTime due = retry ? now.plus(delayMillis(task), ChronoUnit.MILLIS) : null;
        if (attempts.finishInterrupted(started.get(), now, duration) != 1
                || tasks.markRecovered(task, next, due, now) != 1
                || !events.markDeliveryCompleted(event.get(), task.roundNo(), next, now)) {
            throw new IllegalStateException("投递恢复写入失败");
        }
        return true;
    }

    private static long delayMillis(LockedDeliveryTask task) {
        BigDecimal seconds = task.attemptCount() == 1 ? task.retryDelay1Seconds()
                : task.retryDelay2Seconds();
        long nanos = seconds.movePointRight(9).longValueExact();
        return Math.addExact(nanos, 999_999L) / 1_000_000L;
    }
}
