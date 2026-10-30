package com.hookflow.overview.api;

import com.hookflow.common.api.QueryParameters;
import com.hookflow.common.api.QueryParameters.TimeRange;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class OverviewQueryInput {
    private OverviewQueryInput() { }

    static TimeRange parse(String from, String to) {
        if (from == null || to == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return QueryParameters.timeRange(from, to);
    }
}
