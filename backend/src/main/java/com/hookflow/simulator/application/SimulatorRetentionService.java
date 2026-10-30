package com.hookflow.simulator.application;

import com.hookflow.simulator.infrastructure.SimulatorRetentionRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class SimulatorRetentionService {
    private final SimulatorRetentionRepository repository;

    public SimulatorRetentionService(SimulatorRetentionRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteEventState(String eventId) { repository.deleteState(eventId); }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public int deleteRequests(LocalDateTime cutoff) { return repository.deleteRequests(cutoff); }
}
