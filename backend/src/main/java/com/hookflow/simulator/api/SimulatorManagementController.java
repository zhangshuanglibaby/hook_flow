package com.hookflow.simulator.api;

import com.hookflow.common.api.PageParameters;
import com.hookflow.config.RequestIdFilter;
import com.hookflow.simulator.application.SimulatorManagementService;
import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import com.hookflow.simulator.domain.StoredSimulatorConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Profile("!skeleton")
@RestController
public class SimulatorManagementController {
    private final SimulatorManagementService management;

    public SimulatorManagementController(SimulatorManagementService management) { this.management = management; }

    @GetMapping(path = "/api/v1/admin/simulator/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationView configuration() { return view(management.configuration()); }

    @PutMapping(path = "/api/v1/admin/simulator/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationView update(@RequestBody Map<String, Object> body, Principal principal, HttpServletRequest request) {
        return view(management.update(SimulatorConfigurationInput.parse(body), Long.parseLong(principal.getName()),
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
    }

    @GetMapping(path = "/api/v1/admin/simulator/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public SimulatorManagementService.RequestPage requests(@RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return management.requests(PageParameters.parse(page, size));
    }

    private static ConfigurationView view(StoredSimulatorConfiguration stored) {
        var config = stored.configuration();
        return new ConfigurationView(config.mode(), config.failFirstN(), config.delayMs(), stored.updatedAt().toInstant(ZoneOffset.UTC));
    }

    public record ConfigurationView(Mode mode, int failFirstN, int delayMs, Instant updatedAt) { }
}
