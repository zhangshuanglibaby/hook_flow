package com.hookflow.event.api;

import com.hookflow.event.application.EventQueryService.Filter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class EventQueryInput {
    private static final Set<String> STATUSES = Set.of("PENDING", "DELIVERING", "WAIT_RETRY", "PAUSED", "SUCCESS", "FINAL_FAILED");

    private EventQueryInput() { }

    public static Filter parse(String eventId, String eventType, String status, String endpointId, String from, String to) {
        if (eventId != null) eventId = eventId(eventId);
        if (eventType != null) eventType = EventSubmissionInput.requiredText(eventType);
        if (status != null && !STATUSES.contains(status)) throw badRequest();
        Long endpoint = endpointId == null ? null : positiveLong(endpointId);
        Instant start = from == null ? null : utc(from);
        Instant end = to == null ? null : utc(to);
        if (start != null && end != null && !start.isBefore(end)) throw badRequest();
        return new Filter(eventId, eventType, status, endpoint, databaseBoundary(start), databaseBoundary(end));
    }

    public static String eventId(String value) {
        return EventSubmissionInput.requiredText(value);
    }

    private static long positiveLong(String value) {
        if (!value.matches("[1-9][0-9]*")) throw badRequest();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw badRequest(); }
    }

    private static Instant utc(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")) throw badRequest();
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException exception) { throw badRequest(); }
    }

    private static LocalDateTime databaseBoundary(Instant value) {
        if (value == null) return null;
        // DATETIME(3) 的离散时间点对两个半开区间边界都应向上取整，不能截断后包含错误的毫秒。
        Instant boundary = value.truncatedTo(ChronoUnit.MILLIS);
        if (!boundary.equals(value)) boundary = boundary.plusMillis(1);
        LocalDateTime result = LocalDateTime.ofInstant(boundary, ZoneOffset.UTC);
        if (result.getYear() < 1 || result.getYear() > 9999) throw badRequest();
        return result;
    }

    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
