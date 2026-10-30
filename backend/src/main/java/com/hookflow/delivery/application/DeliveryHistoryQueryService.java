package com.hookflow.delivery.application;

import com.hookflow.delivery.infrastructure.DeliveryHistoryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class DeliveryHistoryQueryService {
    private final DeliveryHistoryRepository repository;

    public DeliveryHistoryQueryService(DeliveryHistoryRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public List<Round> rounds(long eventPk) { return repository.rounds(eventPk); }

    public record Round(long taskId, int roundNo, String status, int attemptCount, int maxAttempts,
            Instant nextRunAt, Instant createdAt, Instant finishedAt, List<Attempt> attempts) { }
    public record Attempt(int attemptNo, String result, Instant startedAt, Instant endedAt, Long durationMs,
            Integer httpStatus, String responseExcerpt, String errorCode, String errorMessage) {
        @Override public String toString() { return "DeliveryAttempt[redacted]"; }
    }
}
