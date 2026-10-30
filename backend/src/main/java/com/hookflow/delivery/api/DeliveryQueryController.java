package com.hookflow.delivery.api;

import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.DeliveryQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!skeleton")
@RestController
@RequestMapping(path = "/api/v1/admin/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeliveryQueryController {
    private final DeliveryQueryService queries;
    public DeliveryQueryController(DeliveryQueryService queries) { this.queries = queries; }

    @GetMapping
    public DeliveryQueryService.DeliveryPage list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType, @RequestParam(required = false) String endpointId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return queries.list(DeliveryQueryInput.parse(status, eventType, endpointId, from, to), PageParameters.parse(page, size));
    }

    @GetMapping("/{taskId}")
    public DeliveryQueryService.Detail detail(@PathVariable String taskId) {
        return queries.detail(DeliveryQueryInput.taskId(taskId));
    }
}
