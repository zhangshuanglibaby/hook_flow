package com.hookflow.endpoint.application;

import com.hookflow.audit.application.AuditLogService;
import com.hookflow.audit.application.AuditLogService.Action;
import com.hookflow.delivery.application.DeliveryPauseService;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.endpoint.infrastructure.EndpointRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class EndpointDisableService {
    private final EndpointRepository endpoints;
    private final DeliveryPauseService tasks;
    private final AuditLogService audits;
    private final Clock clock;

    public EndpointDisableService(EndpointRepository endpoints, DeliveryPauseService tasks,
            AuditLogService audits, Clock clock) {
        this.endpoints = endpoints;
        this.tasks = tasks;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<EndpointConfiguration> disable(long id, long actorUserId, String requestId) {
        var locked = endpoints.findByIdForUpdate(id);
        if (locked.isEmpty() || !locked.get().enabled()) {
            return locked;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ZoneOffset.UTC);
        if (endpoints.disable(id, now) != 1) {
            throw new IllegalStateException("接收端停用数量异常");
        }
        tasks.pauseUnsent(id, now);
        audits.recordSuccess(actorUserId, Action.ENDPOINT_DISABLED, id, now, requestId);
        return Optional.of(endpoints.findById(id)
                .orElseThrow(() -> new IllegalStateException("接收端停用后不可见")));
    }
}
