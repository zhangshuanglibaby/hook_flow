package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryAttemptCountRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryAttemptCountQueryService {
    private final DeliveryAttemptCountRepository attempts;

    public DeliveryAttemptCountQueryService(DeliveryAttemptCountRepository attempts) { this.attempts = attempts; }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public long count(LocalDateTime from, LocalDateTime to) { return attempts.count(from, to); }
}
