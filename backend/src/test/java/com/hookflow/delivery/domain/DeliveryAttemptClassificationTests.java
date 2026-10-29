package com.hookflow.delivery.domain;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.HTTP_FAILURE;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.LOCAL_FAILURE;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.SUCCESS;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeliveryAttemptClassificationTests {
    @Test
    void anySuccessfulHttpStatusIsFinalSuccess() {
        for (int status : new int[] {200, 201, 204, 299}) {
            DeliveryAttemptClassification classification =
                    DeliveryAttemptClassification.fromHttpStatus(status);
            assertEquals(SUCCESS, classification.result(), "status=" + status);
            assertFalse(classification.retryable(), "status=" + status);
        }
    }

    @Test
    void only408429And5xxHttpFailuresHaveRetryEligibility() {
        for (int status : new int[] {408, 429, 500, 503, 599}) {
            DeliveryAttemptClassification classification =
                    DeliveryAttemptClassification.fromHttpStatus(status);
            assertEquals(HTTP_FAILURE, classification.result(), "status=" + status);
            assertTrue(classification.retryable(), "status=" + status);
        }
    }

    @Test
    void redirectsAndOtherClientErrorsAreFinalHttpFailures() {
        for (int status : new int[] {300, 302, 399, 400, 404, 407, 409, 428, 430, 499}) {
            DeliveryAttemptClassification classification =
                    DeliveryAttemptClassification.fromHttpStatus(status);
            assertEquals(HTTP_FAILURE, classification.result(), "status=" + status);
            assertFalse(classification.retryable(), "status=" + status);
        }
    }

    @Test
    void indeterminateNetworkAndProvenLocalFailuresRemainDistinct() {
        assertEquals(UNKNOWN, DeliveryAttemptClassification.unknown().result());
        assertTrue(DeliveryAttemptClassification.unknown().retryable());
        assertEquals(LOCAL_FAILURE, DeliveryAttemptClassification.localFailure().result());
        assertFalse(DeliveryAttemptClassification.localFailure().retryable());
        assertEquals(UNKNOWN, DeliveryAttemptClassification.fromHttpStatus(101).result());
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryAttemptClassification.fromHttpStatus(99));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryAttemptClassification.fromHttpStatus(600));
    }

    @Test
    void inconsistentClassificationsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new DeliveryAttemptClassification(null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryAttemptClassification(SUCCESS, true));
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryAttemptClassification(LOCAL_FAILURE, true));
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryAttemptClassification(UNKNOWN, false));
    }
}
