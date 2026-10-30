package com.hookflow.simulator.domain;

import static org.junit.jupiter.api.Assertions.*;
import static com.hookflow.simulator.domain.SimulatorConfiguration.Mode.*;
import org.junit.jupiter.api.Test;

class SimulatorConfigurationTests {
    @Test void fourModesAndEveryFailureBoundary() {
        assertEquals(200, new SimulatorConfiguration(ALWAYS_200, 0, 0).decide(1, false).plannedStatus());
        assertEquals(500, new SimulatorConfiguration(ALWAYS_500, 0, 0).decide(99, false).plannedStatus());
        for (int n = 0; n <= 10; n++) {
            var config = new SimulatorConfiguration(FAIL_FIRST_N, n, 0);
            for (int count = 1; count <= n + 1; count++) {
                var result = config.decide(count, false);
                assertEquals(count <= n ? 500 : 200, result.plannedStatus());
                assertFalse(result.duplicateProcessed()); assertEquals(0, result.delayMs());
            }
        }
        assertEquals(30000, new SimulatorConfiguration(PROCESSED_THEN_DELAY, 0, 30000).decide(1, false).delayMs());
    }

    @Test void processedAlwaysOverridesModeWithoutDelay() {
        for (var config : new SimulatorConfiguration[] {new SimulatorConfiguration(ALWAYS_200, 0, 0),
                new SimulatorConfiguration(ALWAYS_500, 0, 0), new SimulatorConfiguration(FAIL_FIRST_N, 10, 0),
                new SimulatorConfiguration(PROCESSED_THEN_DELAY, 0, 30000)}) {
            assertEquals(new SimulatorConfiguration.Decision(200, true, 0), config.decide(2, true));
        }
    }

    @Test void configurationMatchesP0AndV1Bounds() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(null, 0, 0));
        for (int n : new int[] {-1, 11}) assertThrows(IllegalArgumentException.class,
                () -> new SimulatorConfiguration(FAIL_FIRST_N, n, 0));
        for (int delay : new int[] {-1, 0, 30001}) assertThrows(IllegalArgumentException.class,
                () -> new SimulatorConfiguration(PROCESSED_THEN_DELAY, 0, delay));
        for (var mode : SimulatorConfiguration.Mode.values()) {
            if (mode != FAIL_FIRST_N) assertThrows(IllegalArgumentException.class,
                    () -> new SimulatorConfiguration(mode, 1, mode == PROCESSED_THEN_DELAY ? 1 : 0));
            if (mode != PROCESSED_THEN_DELAY) assertThrows(IllegalArgumentException.class,
                    () -> new SimulatorConfiguration(mode, 0, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(ALWAYS_200, 0, 0).decide(0, false));
    }
}
