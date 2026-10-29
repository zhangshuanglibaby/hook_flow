package com.hookflow.delivery.application;

import com.hookflow.audit.application.AuditLogService;
import com.hookflow.audit.application.AuditLogService.Action;
import com.hookflow.delivery.infrastructure.DeliveryRedeliveryRepository;
import com.hookflow.endpoint.application.EndpointTaskSnapshotService;
import com.hookflow.event.application.EventRedeliveryAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Profile("!skeleton")
@Service
public class DeliveryRedeliveryService {
    private final EventRedeliveryAccess events;
    private final EndpointTaskSnapshotService endpoints;
    private final DeliveryRedeliveryRepository tasks;
    private final DeliveryTaskPolicy policy;
    private final AuditLogService audits;
    private final Clock clock;

    public DeliveryRedeliveryService(EventRedeliveryAccess events, EndpointTaskSnapshotService endpoints,
            DeliveryRedeliveryRepository tasks, DeliveryTaskPolicy policy, AuditLogService audits, Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.tasks = tasks;
        this.policy = policy;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TaskSummary redeliver(String eventId, long actorUserId, String requestId) {
        if (eventId == null || eventId.isBlank() || eventId.codePointCount(0, eventId.length()) > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var location = events.locate(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var snapshot = endpoints.lockEnabledSnapshot(location.endpointId())
                .orElseThrow(() -> new IllegalStateException("事件接收端缺失"));
        var event = events.lock(location.eventPk(), location.endpointId(), eventId)
                .orElseThrow(() -> new IllegalStateException("事件关联不一致"));
        String taskStatus = tasks.lockLatestStatus(event.eventPk(), event.latestRoundNo())
                .orElseThrow(() -> new IllegalStateException("事件最新任务缺失"));
        if (!event.currentStatus().equals(taskStatus)) {
            throw new IllegalStateException("事件与最新任务状态不一致");
        }
        if (!"FINAL_FAILED".equals(taskStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        int nextRound = Math.addExact(event.latestRoundNo(), 1);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime databaseTime = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        long taskId = tasks.insert(event.eventPk(), nextRound, snapshot, policy, databaseTime);
        events.advance(event, nextRound);
        audits.recordSuccess(actorUserId, Action.EVENT_REDELIVERED, event.eventPk(), databaseTime, requestId);
        return new TaskSummary(taskId, event.eventId(), event.eventType(), event.endpointId(), nextRound,
                "PENDING", 0, policy.maxAttempts(), null, now);
    }

    public record TaskSummary(long taskId, String eventId, String eventType, long endpointId,
            int roundNo, String status, int attemptCount, int maxAttempts, Instant lastAttemptAt,
            Instant createdAt) { }
}
