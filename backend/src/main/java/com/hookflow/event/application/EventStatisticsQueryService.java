package com.hookflow.event.application;

import com.hookflow.event.infrastructure.EventStatisticsRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class EventStatisticsQueryService {
    private final EventStatisticsRepository events;

    public EventStatisticsQueryService(EventStatisticsRepository events) { this.events = events; }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Counts count(LocalDateTime from, LocalDateTime to) { return events.count(from, to); }

    public record Counts(long pending, long delivering, long waitRetry, long paused, long success, long finalFailed) {
        public long total() {
            long total = Math.addExact(pending, delivering);
            total = Math.addExact(total, waitRetry);
            total = Math.addExact(total, paused);
            total = Math.addExact(total, success);
            return Math.addExact(total, finalFailed);
        }

        public Map<String, Long> statusCounts() {
            return Map.of("PENDING", pending, "DELIVERING", delivering, "WAIT_RETRY", waitRetry,
                    "PAUSED", paused, "SUCCESS", success, "FINAL_FAILED", finalFailed);
        }
    }
}
