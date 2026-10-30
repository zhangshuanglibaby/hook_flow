package com.hookflow.audit.api;

import com.hookflow.audit.application.AuditQueryService;
import com.hookflow.audit.application.AuditQueryService.AuditPage;
import com.hookflow.common.api.PageParameters;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!skeleton")
@RestController
public class AuditQueryController {
    private final AuditQueryService queries;

    public AuditQueryController(AuditQueryService queries) { this.queries = queries; }

    @GetMapping("/api/v1/admin/audit-logs")
    public AuditPage list(@RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return queries.list(PageParameters.parse(page, size));
    }
}
