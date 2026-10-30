package com.hookflow.simulator.application;

import com.hookflow.event.application.EventRetentionService;
import com.hookflow.simulator.domain.SimulatorConfiguration.Decision;
import com.hookflow.simulator.infrastructure.SimulatorRepository;
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
public class SimulatorProcessingService {
    private final SimulatorRepository repository;
    private final Clock clock;
    private final EventRetentionService events;

    public SimulatorProcessingService(SimulatorRepository repository, Clock clock,
            EventRetentionService events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Decision receiveVerified(String eventId) { return receiveVerified(eventId, null); }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Decision receiveVerified(String eventId, Long expectedPk) {
        var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        var config = repository.configuration(now);
        // 验签与处理是两个事务；共享事件锁避免保留清理后重建旧幂等状态。
        if (!events.lockLiveForSimulator(eventId, expectedPk)) throw SimulatorRequestAuthentication.unauthorized();
        var state = repository.lockState(eventId);
        int count = Math.addExact(state.receivedCount(), 1);
        var decision = config.decide(count, state.processedAt() != null);
        repository.recordReception(eventId, count);
        if (decision.plannedStatus() == 200 && !decision.duplicateProcessed()) {
            // processed_at 是模拟业务副作用；在持锁事务内只执行首次写入。
            repository.processOnce(eventId, now);
        }
        repository.log(eventId, now, true, decision.plannedStatus(), decision.duplicateProcessed(), config.mode());
        return decision;
    }
}
