package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository;
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository.LeaseCandidate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!skeleton")
@Service
public class ExpiredDeliveryRecovery {
    private static final int BATCH_SIZE = 16;
    private final DueDeliveryTaskRepository tasks;
    private final DeliveryRecoveryService recovery;
    private final Clock clock;
    private LocalDateTime lastLeaseUntil;
    private long lastTaskId;

    public ExpiredDeliveryRecovery(DueDeliveryTaskRepository tasks,
            DeliveryRecoveryService recovery, Clock clock) {
        this.tasks = tasks;
        this.recovery = recovery;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverExpiredTasks();
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public synchronized void recoverExpiredTasks() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("不能在数据库事务中扫描恢复投递");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ZoneOffset.UTC);
        List<LeaseCandidate> candidates = tasks.findExpiredLeaseCandidates(now, lastLeaseUntil, lastTaskId);
        if (candidates.isEmpty() && lastLeaseUntil != null) {
            lastLeaseUntil = null;
            lastTaskId = 0;
            candidates = tasks.findExpiredLeaseCandidates(now, null, 0);
        }
        for (LeaseCandidate candidate : candidates) {
            recovery.recover(candidate.id(), candidate.claimToken());
            lastLeaseUntil = candidate.leaseUntil();
            lastTaskId = candidate.id();
        }
        if (candidates.size() < BATCH_SIZE
                && (candidates.isEmpty() || lastTaskId == candidates.getLast().id())) {
            lastLeaseUntil = null;
            lastTaskId = 0;
        }
    }
}
