package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryRetentionRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryRetentionService {
    private final DeliveryRetentionRepository tasks;

    public DeliveryRetentionService(DeliveryRetentionRepository tasks) { this.tasks = tasks; }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean eligible(long eventPk, int roundNo, String status, LocalDateTime terminalAt) {
        return tasks.eligible(eventPk, roundNo, status, terminalAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteHistory(long eventPk) { tasks.deleteHistory(eventPk); }
}
