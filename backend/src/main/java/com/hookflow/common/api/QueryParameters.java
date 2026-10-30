package com.hookflow.common.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class QueryParameters {
    private QueryParameters() { }

    public static String text(String value, int maxCodePoints) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) throw badRequest();
        return value;
    }

    public static long positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw badRequest();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw badRequest(); }
    }

    public static TimeRange timeRange(String from, String to) {
        Instant start = from == null ? null : utc(from);
        Instant end = to == null ? null : utc(to);
        if (start != null && end != null && !start.isBefore(end)) throw badRequest();
        return new TimeRange(databaseBoundary(start), databaseBoundary(end));
    }

    private static Instant utc(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")) throw badRequest();
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException exception) { throw badRequest(); }
    }

    private static LocalDateTime databaseBoundary(Instant value) {
        if (value == null) return null;
        // DATETIME(3) 的离散时间点要求半开区间的两个边界均向上取整。
        Instant boundary = value.truncatedTo(ChronoUnit.MILLIS);
        if (!boundary.equals(value)) boundary = boundary.plusMillis(1);
        LocalDateTime result = LocalDateTime.ofInstant(boundary, ZoneOffset.UTC);
        if (result.getYear() < 1 || result.getYear() > 9999) throw badRequest();
        return result;
    }

    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    public record TimeRange(LocalDateTime from, LocalDateTime to) { }
}
