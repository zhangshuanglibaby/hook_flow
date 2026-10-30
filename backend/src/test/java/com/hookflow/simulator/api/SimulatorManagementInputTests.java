package com.hookflow.simulator.api;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.common.api.PageParameters;
import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SimulatorManagementInputTests {
    @Test void legalModesDefaultsAndExactBoundaries() {
        for (String mode : List.of("ALWAYS_200", "ALWAYS_500", "FAIL_FIRST_N")) {
            var value = SimulatorConfigurationInput.parse(Map.of("mode", mode));
            assertEquals(Mode.valueOf(mode), value.mode()); assertEquals(0, value.failFirstN()); assertEquals(0, value.delayMs());
        }
        for (int n : new int[] {0, 10}) assertEquals(n,
                SimulatorConfigurationInput.parse(Map.of("mode", "FAIL_FIRST_N", "failFirstN", n)).failFirstN());
        for (int ms : new int[] {1, 30000}) assertEquals(ms,
                SimulatorConfigurationInput.parse(Map.of("mode", "PROCESSED_THEN_DELAY", "delayMs", ms)).delayMs());
        assertEquals(0, SimulatorConfigurationInput.parse(Map.of("mode", "ALWAYS_500", "delayMs", 0, "failFirstN", 0)).delayMs());
    }

    @Test void requiredModeUnknownFieldsWrongTypesAndNonApplicableParametersAreRejected() {
        invalid(null); invalid(Map.of()); invalid(Map.of("mode", "always_200")); invalid(Map.of("mode", "OTHER"));
        invalid(Map.of("mode", true)); invalid(Map.of("mode", 1)); invalid(Collections.singletonMap("mode", null));
        invalid(Map.of("mode", "ALWAYS_200", "unknown", 0));
        for (String mode : List.of("ALWAYS_200", "ALWAYS_500", "PROCESSED_THEN_DELAY")) {
            var body = new HashMap<String,Object>(); body.put("mode", mode); body.put("failFirstN", 1);
            if (mode.equals("PROCESSED_THEN_DELAY")) body.put("delayMs", 1);
            invalid(body);
        }
        for (String mode : List.of("ALWAYS_200", "ALWAYS_500", "FAIL_FIRST_N")) invalid(Map.of("mode", mode, "delayMs", 1));
        invalid(Map.of("mode", "PROCESSED_THEN_DELAY"));
    }

    @Test void explicitNullFractionStringBooleanAndOutOfRangeNeverCoerceToIntegers() {
        for (Object raw : Arrays.asList(null, -1, 11, 1.0, "1", true, Long.MAX_VALUE, List.of(1), Map.of())) {
            var body = new HashMap<String,Object>(); body.put("mode", "FAIL_FIRST_N"); body.put("failFirstN", raw); invalid(body);
        }
        for (Object raw : Arrays.asList(null, -1, 0, 30001, 1.0, "1", false, Long.MAX_VALUE, List.of(1), Map.of())) {
            var body = new HashMap<String,Object>(); body.put("mode", "PROCESSED_THEN_DELAY"); body.put("delayMs", raw); invalid(body);
        }
    }

    @Test void paginationDefaultsBoundsAndLongOffsetAvoidOverflow() {
        assertEquals(new PageParameters(1,20), PageParameters.parse(null,null));
        assertEquals(0, PageParameters.parse("1","1").offset());
        assertEquals(214748364600L, PageParameters.parse("2147483647","100").offset());
        for (String raw : List.of("", "0", "-1", "1.0", "1e1", " 1", "+1", "01", "true", "2147483648", "9".repeat(100))) {
            assertThrows(ResponseStatusException.class, () -> PageParameters.parse(raw,null));
            assertThrows(ResponseStatusException.class, () -> PageParameters.parse(null,raw));
        }
        assertThrows(ResponseStatusException.class, () -> PageParameters.parse(null,"101"));
        assertThrows(ResponseStatusException.class, () -> new PageParameters(0,20));
    }

    private void invalid(Map<String,Object> body) {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> SimulatorConfigurationInput.parse(body)).getStatusCode().value());
    }
}
