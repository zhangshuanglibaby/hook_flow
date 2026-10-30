package com.hookflow.delivery.application;

import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.DeliveryHistoryQueryService.Attempt;
import com.hookflow.delivery.application.DeliveryRedeliveryService.TaskSummary;
import com.hookflow.delivery.infrastructure.DeliveryQueryRepository;
import com.hookflow.event.application.EventDeliveryQueryAccess;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Profile("!skeleton")
@Service
public class DeliveryQueryService {
    private final DeliveryQueryRepository tasks;
    private final DeliveryHistoryQueryService history;
    private final EventDeliveryQueryAccess events;

    public DeliveryQueryService(DeliveryQueryRepository tasks, DeliveryHistoryQueryService history,
            EventDeliveryQueryAccess events) {
        this.tasks = tasks;
        this.history = history;
        this.events = events;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public DeliveryPage list(Filter filter, PageParameters page) {
        var selection = tasks.selection(events.select(filter.eventType(), filter.endpointId(), filter.from(), filter.to()), filter.status());
        long total = tasks.count(selection);
        var items = page.offset() >= total ? List.<TaskSummary>of() : tasks.page(selection, page);
        return new DeliveryPage(page.page(), page.size(), total, items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Detail detail(long taskId) {
        var stored = history.task(taskId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String eventId = events.eventId(stored.eventPk());
        var round = stored.round();
        return new Detail(round.taskId(), eventId, round.roundNo(), round.status(), round.attemptCount(),
                round.maxAttempts(), round.nextRunAt(), round.createdAt(), round.finishedAt(), history.attempts(taskId));
    }

    public record Filter(String status, String eventType, Long endpointId, LocalDateTime from, LocalDateTime to) { }
    public record DeliveryPage(int page, int size, long total, List<TaskSummary> items) { }
    public record Detail(long taskId, String eventId, int roundNo, String status, int attemptCount, int maxAttempts,
            Instant nextRunAt, Instant createdAt, Instant finishedAt, List<Attempt> attempts) {
        @Override public String toString() { return "DeliveryDetail[redacted]"; }
    }
}
