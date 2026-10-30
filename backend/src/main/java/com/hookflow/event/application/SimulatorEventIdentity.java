package com.hookflow.event.application;

import com.hookflow.event.infrastructure.EventRepository;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!skeleton")
@Service
public class SimulatorEventIdentity {
    private final EventRepository events;

    public SimulatorEventIdentity(EventRepository events) {
        this.events = events;
    }

    public Optional<Identity> find(String eventId) {
        return events.findSimulatorIdentity(eventId)
                .map(row -> new Identity(row.eventPk(), row.endpointId(), row.latestRoundNo()));
    }

    public record Identity(long eventPk, long endpointId, int latestRoundNo) { }
}
