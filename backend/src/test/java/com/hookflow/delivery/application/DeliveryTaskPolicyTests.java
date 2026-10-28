package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeliveryTaskPolicyTests {
    @Test
    void usesDefaultsWhenConfigurationIsAbsent() {
        DeliveryTaskPolicy policy = new DeliveryTaskPolicy(new MockEnvironment());

        assertEquals(3, policy.maxAttempts());
        assertEquals(new BigDecimal("10.000000000"), policy.retryDelay1Seconds());
        assertEquals(new BigDecimal("30.000000000"), policy.retryDelay2Seconds());
    }

    @Test
    void keepsFractionalSecondsWithoutFloatingPointRounding() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("hookflow.delivery.max-attempts", "1")
                .withProperty("hookflow.delivery.retry-delays", "250ms,1ns");

        DeliveryTaskPolicy policy = new DeliveryTaskPolicy(environment);

        assertEquals(1, policy.maxAttempts());
        assertEquals(new BigDecimal("0.250000000"), policy.retryDelay1Seconds());
        assertEquals(new BigDecimal("0.000000001"), policy.retryDelay2Seconds());
    }

    @Test
    void rejectsInvalidAttemptsAndRetryDelays() {
        for (String attempts : new String[] {"0", "4", "1.5", "invalid"}) {
            assertThrows(IllegalStateException.class, () -> new DeliveryTaskPolicy(
                    new MockEnvironment().withProperty("hookflow.delivery.max-attempts", attempts)));
        }
        for (String delays : new String[] {"", "10s", "10s,", "1s,2s,3s",
                "0s,1s", "-1s,1s", "invalid,1s"}) {
            assertThrows(IllegalStateException.class, () -> new DeliveryTaskPolicy(
                    new MockEnvironment().withProperty("hookflow.delivery.retry-delays", delays)));
        }
    }
}
