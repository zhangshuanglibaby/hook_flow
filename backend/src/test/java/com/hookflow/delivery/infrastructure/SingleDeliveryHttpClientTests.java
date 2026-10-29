package com.hookflow.delivery.infrastructure;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.LOCAL_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.mock.env.MockEnvironment;

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
                SingleDeliveryHttpClient client = clientForSimulator(clock, receiver)) {
            assertEquals(200, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());

            ReceivedRequest received = receiver.awaitRequest(WAIT);
            assertNotNull(received);
            assertEquals("POST", received.method());
            assertEquals(SimulatorInternalTargetRoute.PATH, received.uri().getPath());
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
                SingleDeliveryHttpClient client = clientForSimulator(clock, receiver)) {
            assertEquals(200, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
            ReceivedRequest first = receiver.awaitRequest(WAIT);
            assertNotNull(first);

            clock.advance(Duration.ofSeconds(1));
            assertEquals(200, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
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
                SingleDeliveryHttpClient client = clientForSimulator(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), source)) {
            source.respondWithRedirect(destination.uri());

            assertEquals(302, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
            assertNotNull(source.awaitRequest(WAIT));
            assertNull(source.awaitRequest(NO_EXTRA_REQUEST));
            assertNull(destination.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void returnsErrorStatusWithoutAutomaticRetry() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = clientForSimulator(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), receiver)) {
            receiver.respondWith(503, "try later");

            assertEquals(503, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
            assertNotNull(receiver.awaitRequest(WAIT));
            assertNull(receiver.awaitRequest(NO_EXTRA_REQUEST));
        }
    }

    @Test
    void doesNotSendWhenSigningSecretIsMissing() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = clientForSimulator(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), receiver)) {
            assertEquals(LOCAL_FAILURE, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), "", 5000).classification().result());
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
                SingleDeliveryHttpClient client = clientForSimulator(
                        new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), receiver)) {
            assertEquals(200, client.postSimulatorOnce(EVENT_ID,
                    BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
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

    @Test
    void simulatorConnectionDoesNotUseJvmSocksProxy() throws Exception {
        String previousHost = System.getProperty("socksProxyHost");
        String previousPort = System.getProperty("socksProxyPort");
        try {
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", "1");
            try (TestHttpReceiver receiver = TestHttpReceiver.start();
                    SingleDeliveryHttpClient client = clientForSimulator(
                            new MutableClock(Instant.parse("2026-10-29T00:00:00Z")), receiver)) {
                assertEquals(200, client.postSimulatorOnce(EVENT_ID,
                        BODY.getBytes(StandardCharsets.UTF_8), SECRET, 5000).httpStatus());
                assertNotNull(receiver.awaitRequest(WAIT));
            }
        } finally {
            restoreProperty("socksProxyHost", previousHost);
            restoreProperty("socksProxyPort", previousPort);
        }
    }

    private static SingleDeliveryHttpClient clientForSimulator(MutableClock clock,
            TestHttpReceiver receiver) {
        SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                new MockEnvironment().withProperty("local.server.port",
                        Integer.toString(receiver.simulatorUri().getPort())));
        return new SingleDeliveryHttpClient(clock, new DeliveryRequestSigner(), route);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
