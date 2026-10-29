package com.hookflow.event.application;

import com.hookflow.event.infrastructure.EventRepository;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class EventRedeliveryAccess {
    private final EventRepository events;

    public EventRedeliveryAccess(EventRepository events) {
        this.events = events;
    }

    public Optional<Location> locate(String eventId) {
        return events.findRedeliveryLocation(eventId)
                .map(row -> new Location(row.eventPk(), row.endpointId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedEvent> lock(long eventPk, long endpointId, String eventId) {
        return events.lockForRedelivery(eventPk)
                .filter(row -> row.endpointId() == endpointId && row.eventId().equals(eventId))
                .map(row -> new LockedEvent(row.id(), row.eventId(), row.eventType(), row.endpointId(),
                        row.latestRoundNo(), row.currentStatus()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(LockedEvent event, int nextRound) {
        if (!"FINAL_FAILED".equals(event.currentStatus())
                || nextRound != Math.addExact(event.latestRoundNo(), 1)) {
            throw new IllegalArgumentException("事件重投状态无效");
        }
        if (events.markRedelivered(new EventRepository.RedeliveryEvent(event.eventPk(), event.eventId(),
                event.eventType(), event.endpointId(), event.latestRoundNo(), event.currentStatus()), nextRound) != 1) {
            throw new IllegalStateException("事件重投同步失败");
        }
    }

    public record Location(long eventPk, long endpointId) { }
    public record LockedEvent(long eventPk, String eventId, String eventType, long endpointId,
            int latestRoundNo, String currentStatus) { }
}
