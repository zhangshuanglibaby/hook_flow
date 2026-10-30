package com.hookflow.event.application;

import com.hookflow.audit.application.AuditRetentionService;
import com.hookflow.simulator.application.SimulatorRetentionService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!skeleton")
@Service
public class RetentionCleanup {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);
    private final EventRetentionService events;
    private final SimulatorRetentionService simulator;
    private final AuditRetentionService audits;
    private final Clock clock;
    private long afterId;

    public RetentionCleanup(EventRetentionService events, SimulatorRetentionService simulator,
            AuditRetentionService audits, Clock clock) {
        this.events = events;
        this.simulator = simulator;
        this.audits = audits;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${hookflow.retention.initial-delay-ms:60000}",
            fixedDelayString = "${hookflow.retention.fixed-delay-ms:60000}")
    public synchronized void cleanExpired() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("保留扫描不能加入事务");
        var cutoff = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC).minusDays(30);
        List<Long> candidates;
        try {
            candidates = events.candidates(cutoff, afterId);
            if (candidates.isEmpty() && afterId != 0) { afterId = 0; candidates = events.candidates(cutoff, 0); }
        } catch (RuntimeException exception) {
            log.warn("eventRetentionScanFailed exceptionType={}", exception.getClass().getSimpleName());
            candidates = List.of();
        }
        for (long id : candidates) {
            try { events.deleteExpired(id); }
            catch (RuntimeException exception) { log.warn("eventRetentionFailed exceptionType={}", exception.getClass().getSimpleName()); }
            afterId = id;
        }
        if (candidates.size() < 16) afterId = 0;
        try { simulator.deleteRequests(cutoff); }
        catch (RuntimeException exception) { log.warn("simulatorRetentionFailed exceptionType={}", exception.getClass().getSimpleName()); }
        try { audits.deleteExpired(cutoff); }
        catch (RuntimeException exception) { log.warn("auditRetentionFailed exceptionType={}", exception.getClass().getSimpleName()); }
    }
}
