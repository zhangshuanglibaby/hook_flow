package com.hookflow.audit.application;

import com.hookflow.audit.infrastructure.AuditQueryRepository;
import com.hookflow.common.api.PageParameters;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class AuditQueryService {
    private final AuditQueryRepository audits;

    public AuditQueryService(AuditQueryRepository audits) { this.audits = audits; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AuditPage list(PageParameters page) {
        long total = audits.count();
        var items = page.offset() >= total ? List.<Entry>of() : audits.page(page);
        return new AuditPage(page.page(), page.size(), total, items);
    }

    public record AuditPage(int page, int size, long total, List<Entry> items) { }
    public record Entry(long id, long actorUserId, String action, String objectType, String objectId,
            Instant occurredAt, String requestId) { }
}
