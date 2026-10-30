package com.hookflow.event.api;

import com.hookflow.common.api.QueryParameters;
import com.hookflow.event.application.EventQueryService.Filter;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class EventQueryInput {
    private static final Set<String> STATUSES = Set.of("PENDING", "DELIVERING", "WAIT_RETRY", "PAUSED", "SUCCESS", "FINAL_FAILED");

    private EventQueryInput() { }

    public static Filter parse(String eventId, String eventType, String status, String endpointId, String from, String to) {
        if (eventId != null) eventId = eventId(eventId);
        if (eventType != null) eventType = QueryParameters.text(eventType, 128);
        if (status != null && !STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Long endpoint = endpointId == null ? null : QueryParameters.positiveLong(endpointId);
        var range = QueryParameters.timeRange(from, to);
        return new Filter(eventId, eventType, status, endpoint, range.from(), range.to());
    }

    public static String eventId(String value) { return QueryParameters.text(value, 128); }
}
