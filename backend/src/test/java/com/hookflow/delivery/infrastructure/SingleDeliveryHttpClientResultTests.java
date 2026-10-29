package com.hookflow.delivery.infrastructure;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.HTTP_FAILURE;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hookflow.support.MutableClock;
import com.hookflow.support.TestHttpReceiver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SingleDeliveryHttpClientResultTests {
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @MethodSource("statusCases")
    void classifiesRealLocalHttpResponses(int status, boolean retryable) throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = client(receiver)) {
            receiver.respondWithRetryAfter(status, "response-" + status, "0");

            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 5000);

            assertEquals(status, result.httpStatus());
            assertEquals(status == 200 ? SUCCESS : HTTP_FAILURE, result.classification().result());
            assertEquals(retryable, result.classification().retryable());
            assertEquals("response-" + status, result.responseExcerpt());
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(1)));
            assertNull(receiver.awaitRequest(Duration.ofMillis(100)));
        }
    }

    private static Stream<Arguments> statusCases() {
        return Stream.of(
                Arguments.of(200, false),
                Arguments.of(500, true),
                Arguments.of(408, true),
                Arguments.of(429, true),
                Arguments.of(302, false),
                Arguments.of(400, false),
                Arguments.of(404, false));
    }

    @Test
    void keepsOnlyFourKiBOfLongRealHttpResponse() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = client(receiver)) {
            receiver.respondWith(200, "a".repeat(4095) + "中" + "b".repeat(100_000));

            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 5000);

            assertEquals(200, result.httpStatus());
            assertEquals(SUCCESS, result.classification().result());
            assertEquals("a".repeat(4095), result.responseExcerpt());
            assertEquals(4095, result.responseExcerpt().getBytes(StandardCharsets.UTF_8).length);
            assertNotNull(receiver.awaitRequest(Duration.ofSeconds(1)));
        }
    }

    private static SingleDeliveryHttpClient client(TestHttpReceiver receiver) {
        SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                new MockEnvironment().withProperty("local.server.port",
                        Integer.toString(receiver.simulatorUri().getPort())));
        return new SingleDeliveryHttpClient(
                new MutableClock(Instant.parse("2026-10-29T00:00:00Z")),
                new DeliveryRequestSigner(), route);
    }
}
