package com.hookflow.simulator.application;

import com.hookflow.audit.application.AuditLogService;
import com.hookflow.audit.application.AuditLogService.Action;
import com.hookflow.common.api.PageParameters;
import com.hookflow.simulator.domain.SimulatorConfiguration;
import com.hookflow.simulator.domain.SimulatorRequestRecord;
import com.hookflow.simulator.domain.StoredSimulatorConfiguration;
import com.hookflow.simulator.infrastructure.SimulatorRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class SimulatorManagementService {
    private final SimulatorRepository repository;
    private final AuditLogService audit;
    private final Clock clock;

    public SimulatorManagementService(SimulatorRepository repository, AuditLogService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoredSimulatorConfiguration configuration() {
        repository.configuration(now());
        return repository.storedConfiguration(false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public StoredSimulatorConfiguration update(SimulatorConfiguration requested, long actorUserId, String requestId) {
        if (requested == null) throw new IllegalArgumentException("模拟器配置缺失");
        var now = now();
        repository.configuration(now);
        var current = repository.storedConfiguration(true);
        if (current.configuration().equals(requested)) return current;
        repository.updateConfiguration(requested, now);
        audit.recordSuccess(actorUserId, Action.SIMULATOR_CONFIG_UPDATED, 1, now, requestId);
        return new StoredSimulatorConfiguration(requested, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public RequestPage requests(PageParameters page) {
        long total = repository.requestCount();
        var items = total <= page.offset() ? List.<SimulatorRequestRecord>of() : repository.requests(page.size(), page.offset());
        return new RequestPage(page.page(), page.size(), total, items);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    public record RequestPage(int page, int size, long total, List<SimulatorRequestRecord> items) { }
}
