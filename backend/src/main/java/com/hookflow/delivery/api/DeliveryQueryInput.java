package com.hookflow.delivery.api;

import com.hookflow.common.api.QueryParameters;
import com.hookflow.delivery.application.DeliveryQueryService.Filter;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class DeliveryQueryInput {
    private static final Set<String> STATUSES = Set.of("PENDING", "DELIVERING", "WAIT_RETRY", "PAUSED", "SUCCESS", "FINAL_FAILED");
    private DeliveryQueryInput() { }

    public static Filter parse(String status, String eventType, String endpointId, String from, String to) {
        if (status != null && !STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (eventType != null) eventType = QueryParameters.text(eventType, 128);
        Long endpoint = endpointId == null ? null : QueryParameters.positiveLong(endpointId);
        var range = QueryParameters.timeRange(from, to);
        return new Filter(status, eventType, endpoint, range.from(), range.to());
    }

    public static long taskId(String value) { return QueryParameters.positiveLong(value); }
}
