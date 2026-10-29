package com.hookflow.delivery.infrastructure;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.HTTP_FAILURE;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.LOCAL_FAILURE;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.SUCCESS;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.delivery.domain.DeliveryAttemptClassification;
import org.junit.jupiter.api.Test;

class SingleDeliveryHttpResultTests {
    @Test
    void keepsHttpStatusExcerptAndContractClassification() {
        SingleDeliveryHttpResult success = SingleDeliveryHttpResult.fromHttpResponse(200, "ok");
        SingleDeliveryHttpResult retryable = SingleDeliveryHttpResult.fromHttpResponse(429, "");
        SingleDeliveryHttpResult finalFailure = SingleDeliveryHttpResult.fromHttpResponse(302, "redirect");

        assertEquals(SUCCESS, success.classification().result());
        assertEquals(200, success.httpStatus());
        assertEquals("ok", success.responseExcerpt());
        assertEquals(HTTP_FAILURE, retryable.classification().result());
        assertTrue(retryable.classification().retryable());
        assertEquals("", retryable.responseExcerpt());
        assertEquals(HTTP_FAILURE, finalFailure.classification().result());
        assertFalse(finalFailure.classification().retryable());
    }

    @Test
    void doesNotInventHttpResponseForUncertainOrPreSendFailure() {
        SingleDeliveryHttpResult unknown = SingleDeliveryHttpResult.unknown();
        SingleDeliveryHttpResult localFailure = SingleDeliveryHttpResult.localFailure();

        assertEquals(UNKNOWN, unknown.classification().result());
        assertTrue(unknown.classification().retryable());
        assertNull(unknown.httpStatus());
        assertNull(unknown.responseExcerpt());
        assertEquals(LOCAL_FAILURE, localFailure.classification().result());
        assertFalse(localFailure.classification().retryable());
        assertNull(localFailure.httpStatus());
        assertNull(localFailure.responseExcerpt());
    }

    @Test
    void enforcesUtf8ByteLimitRatherThanCharacterCount() {
        assertEquals(4096, SingleDeliveryHttpResult.fromHttpResponse(200, "中".repeat(1365) + "a")
                .responseExcerpt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class,
                () -> SingleDeliveryHttpResult.fromHttpResponse(200, "中".repeat(1366)));
    }

    @Test
    void rejectsContradictoryResultsAndMissingExcerpts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SingleDeliveryHttpResult(DeliveryAttemptClassification.unknown(), 200, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SingleDeliveryHttpResult(DeliveryAttemptClassification.localFailure(), null, "body"));
        assertThrows(IllegalArgumentException.class,
                () -> new SingleDeliveryHttpResult(DeliveryAttemptClassification.fromHttpStatus(200), null, null));
        assertThrows(NullPointerException.class,
                () -> SingleDeliveryHttpResult.fromHttpResponse(200, null));
        assertThrows(IllegalArgumentException.class,
                () -> SingleDeliveryHttpResult.fromHttpResponse(100, ""));
    }

    @Test
    void doesNotIncludeResponseBodyInToString() {
        SingleDeliveryHttpResult result = SingleDeliveryHttpResult.fromHttpResponse(500, "sensitive-body");

        assertFalse(result.toString().contains("sensitive-body"));
        assertTrue(result.toString().contains("HTTP_FAILURE"));
    }
}
