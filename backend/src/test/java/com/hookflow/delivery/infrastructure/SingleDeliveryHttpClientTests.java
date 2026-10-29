package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hookflow.support.MutableClock;
import com.hookflow.support.TestHttpReceiver;
import com.hookflow.support.TestHttpReceiver.ReceivedRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SingleDeliveryHttpClientTests {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration NO_EXTRA_REQUEST = Duration.ofMillis(250);
    private static final String EVENT_ID = "evt-001";
    private static final String SECRET = "hookflow-test-key";
    private static final String BODY = "{\"message\":\"你好\",\"value\":1}";

    @Test
    void sendsCanonicalUtf8BytesAndSignedHeadersOnce() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T00:00:00Z"));
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(clock, new DeliveryRequestSigner())) {
            assertEquals(200, client.postOnce(receiver.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));

            ReceivedRequest received = receiver.awaitRequest(WAIT);
            assertNotNull(received);
            assertEquals("POST", received.method());
            assertEquals("/receive", received.uri().getPath());
            assertEquals(BODY, received.body());
            assertEquals("application/json", received.headers().getFirst("Content-Type"));
            assertEquals(EVENT_ID, received.headers().getFirst("X-HookFlow-Event-Id"));
            assertEquals("2026-10-29T00:00:00Z", received.headers().getFirst("X-HookFlow-Timestamp"));
            assertEquals("710bdb35ae3f6919f44f5847235254c1273fe1c258313c1dabf1d4329945e938",
                    received.headers().getFirst("X-HookFlow-Signature"));
            assertFalse(received.headers().values().stream().flatMap(values -> values.stream())
                    .anyMatch(value -> value.contains(SECRET)));
            assertNull(receiver.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void recalculatesTimestampAndSignatureForEachExplicitAttempt() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T00:00:00Z"));
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(clock, new DeliveryRequestSigner())) {
            assertEquals(200, client.postOnce(receiver.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));
            ReceivedRequest first = receiver.awaitRequest(WAIT);
            assertNotNull(first);

            clock.advance(Duration.ofSeconds(1));
            assertEquals(200, client.postOnce(receiver.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));
            ReceivedRequest second = receiver.awaitRequest(WAIT);
            assertNotNull(second);
            assertEquals(EVENT_ID, second.headers().getFirst("X-HookFlow-Event-Id"));
            assertEquals("2026-10-29T00:00:01Z", second.headers().getFirst("X-HookFlow-Timestamp"));
            assertNotEquals(first.headers().getFirst("X-HookFlow-Signature"),
                    second.headers().getFirst("X-HookFlow-Signature"));
            assertNull(receiver.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void returns302WithoutFollowingLocation() throws Exception {
        try (TestHttpReceiver source = TestHttpReceiver.start();
                TestHttpReceiver destination = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), new DeliveryRequestSigner())) {
            source.respondWithRedirect(destination.uri());

            assertEquals(302, client.postOnce(source.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));
            assertNotNull(source.awaitRequest(WAIT));
            assertNull(source.awaitRequest(NO_EXTRA_REQUEST));
            assertNull(destination.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void returnsErrorStatusWithoutAutomaticRetry() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), new DeliveryRequestSigner())) {
            receiver.respondWith(503, "try later");

            assertEquals(503, client.postOnce(receiver.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));
            assertNotNull(receiver.awaitRequest(WAIT));
            assertNull(receiver.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void doesNotSendWhenSigningSecretIsMissing() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), new DeliveryRequestSigner())) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> client.postOnce(receiver.uri().toString(), EVENT_ID,
                            BODY.getBytes(StandardCharsets.UTF_8), ""));
            assertTrue(failure.getMessage().contains("签名密钥缺失"));
            assertNull(receiver.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void ordinaryLogsDoNotContainTheSigningSecretOrSignature() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), new DeliveryRequestSigner())) {
            assertEquals(200, client.postOnce(receiver.uri().toString(), EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET));
            ReceivedRequest received = receiver.awaitRequest(WAIT);
            assertNotNull(received);
            String signature = received.headers().getFirst("X-HookFlow-Signature");
            assertNotNull(signature);
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(SECRET) || message.contains(signature)));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
