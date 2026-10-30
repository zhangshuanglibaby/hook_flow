package com.hookflow.event.api;

import com.hookflow.common.api.PageParameters;
import com.hookflow.event.application.EventQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!skeleton")
@RestController
@RequestMapping(path = "/api/v1/admin/events", produces = MediaType.APPLICATION_JSON_VALUE)
public class EventQueryController {
    private final EventQueryService queries;

    public EventQueryController(EventQueryService queries) { this.queries = queries; }

    @GetMapping
    public EventQueryService.EventPage list(@RequestParam(required = false) String eventId,
            @RequestParam(required = false) String eventType, @RequestParam(required = false) String status,
            @RequestParam(required = false) String endpointId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return queries.list(EventQueryInput.parse(eventId, eventType, status, endpointId, from, to), PageParameters.parse(page, size));
    }

    @GetMapping("/{eventId}")
    public EventQueryService.Detail detail(@PathVariable String eventId) {
        return queries.detail(EventQueryInput.eventId(eventId));
    }
}
