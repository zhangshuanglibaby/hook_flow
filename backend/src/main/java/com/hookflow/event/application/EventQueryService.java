package com.hookflow.event.application;

import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.DeliveryHistoryQueryService;
import com.hookflow.event.infrastructure.EventQueryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Profile("!skeleton")
@Service
public class EventQueryService {
    private final EventQueryRepository events;
    private final DeliveryHistoryQueryService history;
    private final JsonMapper payloadMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public EventQueryService(EventQueryRepository events, DeliveryHistoryQueryService history) {
        this.events = events;
        this.history = history;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public EventPage list(Filter filter, PageParameters page) {
        long total = events.count(filter);
        var items = page.offset() >= total ? List.<Summary>of() : events.page(filter, page);
        return new EventPage(page.page(), page.size(), total, items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Detail detail(String eventId) {
        var event = events.find(eventId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var rounds = history.rounds(event.eventPk());
        if (rounds.size() != event.summary().latestRoundNo() || rounds.isEmpty()) {
            throw new IllegalStateException("事件轮次历史不一致");
        }
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).roundNo() != i + 1) throw new IllegalStateException("事件轮次历史不一致");
        }
        if (!rounds.getLast().status().equals(event.summary().status())) {
            throw new IllegalStateException("事件最新轮状态不一致");
        }
        var summary = event.summary();
        return new Detail(summary.eventId(), summary.eventType(), summary.endpointId(),
                payloadMapper.readTree(event.payloadJson()), summary.status(), summary.latestRoundNo(),
                summary.createdAt(), rounds);
    }

    public record Filter(String eventId, String eventType, String status, Long endpointId,
            LocalDateTime from, LocalDateTime to) { }
    public record Summary(String eventId, String eventType, long endpointId, String status,
            int latestRoundNo, Instant createdAt) { }
    public record EventPage(int page, int size, long total, List<Summary> items) { }
    public record Detail(String eventId, String eventType, long endpointId, JsonNode payload, String status,
            int latestRoundNo, Instant createdAt, List<DeliveryHistoryQueryService.Round> rounds) {
        @Override public String toString() { return "EventDetail[redacted]"; }
    }
}
