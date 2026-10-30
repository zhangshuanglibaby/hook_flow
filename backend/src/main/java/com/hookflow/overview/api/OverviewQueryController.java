package com.hookflow.overview.api;

import com.hookflow.overview.application.OverviewQueryService;
import com.hookflow.overview.application.OverviewQueryService.Overview;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!skeleton")
@RestController
public class OverviewQueryController {
    private final OverviewQueryService overview;

    public OverviewQueryController(OverviewQueryService overview) { this.overview = overview; }

    @GetMapping("/api/v1/admin/overview")
    public Overview get(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        var range = OverviewQueryInput.parse(from, to);
        return overview.query(range.from(), range.to());
    }
}
