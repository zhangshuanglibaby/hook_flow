package com.hookflow.overview.application;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.event.application.EventStatisticsQueryService.Counts;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OverviewQueryServiceTests {
    static final Set<String> STATES = Set.of("PENDING", "DELIVERING", "WAIT_RETRY", "PAUSED", "SUCCESS", "FINAL_FAILED");

    @Test void denominatorOnlyIncludesSuccessAndFinalFailureWithNullZeroOneAndRecurringDecimals() {
        assertRate(null, new Counts(1, 2, 3, 4, 0, 0));
        assertRate("0.0000000000", new Counts(9, 8, 7, 6, 0, 3));
        assertRate("1.0000000000", new Counts(9, 8, 7, 6, 3, 0));
        assertRate("0.5000000000", new Counts(1, 2, 3, 4, 2, 2));
        assertRate("0.3333333333", new Counts(0, 0, 0, 0, 1, 2));
        assertRate("0.6666666667", new Counts(0, 0, 0, 0, 2, 1));
        assertRate("0.0000000001", new Counts(0, 0, 0, 0, 1, 19_999_999_999L));
    }

    @Test void countsAndRateUseLongAndDecimalWithoutIntegerDivisionOrOverflow() {
        long large = 3_000_000_000L;
        var result = OverviewQueryService.summarize(new Counts(large, large, large, large, large, large), Long.MAX_VALUE);
        assertEquals(18_000_000_000L, result.eventCount()); assertEquals(Long.MAX_VALUE, result.attemptCount());
        assertEquals(new BigDecimal("0.5000000000"), result.successRate());
        assertTrue(result.statusCounts().values().stream().allMatch(n -> n == large));
        assertEquals(Long.MAX_VALUE, new Counts(0, 0, 0, 0, Long.MAX_VALUE - 1, 1).total());
        assertThrows(ArithmeticException.class, () -> new Counts(1, 0, 0, 0, Long.MAX_VALUE, 0).total());
    }

    @Test void jsonHasExactlyFourTopLevelFieldsSixNumericStatusKeysAndExplicitNull() {
        var mapper = JsonMapper.builder().build();
        var empty = mapper.readTree(mapper.writeValueAsString(OverviewQueryService.summarize(new Counts(0, 0, 0, 0, 0, 0), 7)));
        assertEquals(Set.of("eventCount", "statusCounts", "successRate", "attemptCount"), empty.propertyNames());
        assertEquals(STATES, empty.path("statusCounts").propertyNames());
        empty.path("statusCounts").forEach(value -> { assertTrue(value.isIntegralNumber()); assertEquals(0, value.asLong()); });
        assertTrue(empty.path("successRate").isNull()); assertEquals(7, empty.path("attemptCount").asLong());
        var partial = mapper.readTree(mapper.writeValueAsString(OverviewQueryService.summarize(new Counts(0, 0, 0, 0, 2, 1), 0)));
        assertTrue(partial.path("successRate").isNumber()); assertEquals(0.6666666667, partial.path("successRate").asDouble());
    }

    static void assertRate(String expected, Counts counts) {
        var result = OverviewQueryService.summarize(counts, 0);
        assertEquals(expected == null ? null : new BigDecimal(expected), result.successRate());
        assertEquals(STATES, result.statusCounts().keySet());
        assertEquals(result.eventCount(), result.statusCounts().values().stream().mapToLong(Long::longValue).sum());
    }
}
