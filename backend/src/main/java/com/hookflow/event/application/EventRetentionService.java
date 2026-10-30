package com.hookflow.event.application;

import com.hookflow.delivery.application.DeliveryRetentionService;
import com.hookflow.endpoint.application.EndpointClaimGuard;
import com.hookflow.event.infrastructure.EventRetentionRepository;
import com.hookflow.simulator.application.SimulatorRetentionService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class EventRetentionService {
    private final EventRetentionRepository events;
    private final EndpointClaimGuard endpoints;
    private final DeliveryRetentionService deliveries;
    private final SimulatorRetentionService simulator;
    private final Clock clock;

    public EventRetentionService(EventRetentionRepository events, EndpointClaimGuard endpoints,
            DeliveryRetentionService deliveries, SimulatorRetentionService simulator, Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.simulator = simulator;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public List<Long> candidates(LocalDateTime cutoff, long afterId) { return events.candidates(cutoff, afterId); }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public boolean deleteExpired(long id) {
        var endpoint = events.endpoint(id);
        if (endpoint.isEmpty()) return false;
        // 停用不阻止终态保留清理；仍按 endpoint → event → task 顺序持锁复查。
        endpoints.lockEnabled(endpoint.get());
        var found = events.lock(id);
        var cutoff = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC).minusDays(30);
        if (found.isEmpty()) return false;
        var event = found.get();
        if (event.endpointId() != endpoint.get() || event.terminalAt() == null || event.terminalAt().isAfter(cutoff)
                || !("SUCCESS".equals(event.status()) || "FINAL_FAILED".equals(event.status()))
                || !deliveries.eligible(event.id(), event.roundNo(), event.status(), event.terminalAt())) return false;
        deliveries.deleteHistory(event.id());
        simulator.deleteEventState(event.eventId());
        events.delete(event);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockLiveForSimulator(String eventId, Long expectedPk) { return events.lockLiveForSimulator(eventId, expectedPk); }
}
