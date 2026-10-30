package com.hookflow.event.application;

import com.hookflow.event.infrastructure.EventQueryRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class EventDeliveryQueryAccess {
    private final EventQueryRepository repository;

    public EventDeliveryQueryAccess(EventQueryRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Selection select(String eventType, Long endpointId, LocalDateTime from, LocalDateTime to) {
        return repository.deliverySelection(eventType, endpointId, from, to);
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public String eventId(long eventPk) {
        return repository.eventId(eventPk).orElseThrow(() -> new IllegalStateException("任务所属事件缺失"));
    }

    // 仅供应用内部组合数据库查询；投影固定为 event_pk/event_id/event_type/endpoint_id/latest_round_no。
    public record Selection(String sql, Map<String, Object> parameters) {
        public Selection { parameters = Map.copyOf(parameters); }
    }
}
