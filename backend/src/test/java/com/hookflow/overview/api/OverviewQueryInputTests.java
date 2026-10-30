package com.hookflow.overview.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OverviewQueryInputTests {
    static final String FROM = "2026-10-30T00:00:00Z";
    static final String TO = "2026-10-31T00:00:00Z";

    @Test void bothUtcBoundsAreRequiredIncludingEmptyAndBlankValues() {
        assertBad(null, null); assertBad(null, TO); assertBad(FROM, null);
        for (String value : List.of("", " ", "\t")) { assertBad(value, TO); assertBad(FROM, value); }
    }

    @Test void strictUtcRejectsDateOffsetsLowercaseInvalidDatesAndFractions() {
        for (String value : List.of("2026-10-30", "2026-10-30t00:00:00Z", "2026-10-30T00:00:00z",
                "2026-10-30T00:00:00+00:00", "2026-10-30T08:00:00+08:00", "2026-02-29T00:00:00Z",
                "2026-13-01T00:00:00Z", "2026-10-30T24:00:00Z", "2026-10-30T00:00:60Z",
                "2026-10-30T00:00:00.Z", "2026-10-30T00:00:00.1234567890Z", "0000-01-01T00:00:00Z",
                "9999-12-31T23:59:59.999000001Z", "2026-10-30T00:00:00Z ")) {
            assertBad(value, TO); assertBad(FROM, value);
        }
        assertBad(FROM, FROM); assertBad(TO, FROM);
    }

    @Test void millisecondAndSubmillisecondBoundsBothRoundUpWithoutRejectingEmptyDiscreteRange() {
        var exact = OverviewQueryInput.parse("2026-10-30T00:00:00.123Z", "2026-10-30T00:00:00.124Z");
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.123"), exact.from());
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.124"), exact.to());
        var fine = OverviewQueryInput.parse("2026-10-30T00:00:00.123000001Z", "2026-10-30T00:00:00.123999999Z");
        assertEquals(exact.to(), fine.from()); assertEquals(exact.to(), fine.to());
        var upper = OverviewQueryInput.parse(FROM, "2026-10-30T00:00:00.123000001Z");
        assertEquals(exact.to(), upper.to());
        assertBad("2026-10-30T00:00:00.123000002Z", "2026-10-30T00:00:00.123000001Z");
        for (int digits = 1; digits <= 9; digits++)
            assertNotNull(OverviewQueryInput.parse("2026-10-30T00:00:00." + "1".repeat(digits) + "Z", TO));
    }

    static void assertBad(String from, String to) {
        var error = assertThrows(ResponseStatusException.class, () -> OverviewQueryInput.parse(from, to));
        assertEquals(400, error.getStatusCode().value());
    }
}
