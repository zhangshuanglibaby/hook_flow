package com.hookflow.delivery.infrastructure;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.UNKNOWN;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.SUCCESS;
import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.HTTP_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hookflow.support.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SingleDeliveryHttpClientNetworkFailureTests {
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    void disconnectAfterReceivingRequestLeavesResultUnknown() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.DISCONNECT);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 1000);

            assertUnknown(result);
            assertTrue(receiver.awaitRequest());
            assertEquals(1, receiver.requestCount());
        }
    }

    @Test
    void timeoutWhileWaitingForResponseHeadersIsUnknown() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.STALL_HEADERS);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 180);

            assertUnknown(result);
            assertTrue(receiver.awaitRequest());
        }
    }

    @Test
    void knownSuccessStatusRemainsSuccessIfBodyReadTimesOut() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.STALL_BODY);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 180);

            assertEquals(SUCCESS, result.classification().result());
            assertEquals(false, result.classification().retryable());
            assertEquals(200, result.httpStatus());
            assertEquals("", result.responseExcerpt());
            assertTrue(receiver.awaitRequest());
        }
    }

    @Test
    void knownServerFailureStatusRemainsHttpFailureIfBodyReadTimesOut() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.STALL_BODY_500);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 180);

            assertEquals(HTTP_FAILURE, result.classification().result());
            assertTrue(result.classification().retryable());
            assertEquals(500, result.httpStatus());
            assertEquals("", result.responseExcerpt());
            assertTrue(receiver.awaitRequest());
        }
    }

    @Test
    void overallTimeoutStopsIncompleteResponseHeadersDespiteIncomingBytes() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.TRICKLE_HEADERS);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            long start = System.nanoTime();
            SingleDeliveryHttpResult result = client.postSimulatorOnce("evt-1", BODY, "secret", 250);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertUnknown(result);
            assertTrue(receiver.awaitRequest());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, elapsed.toString());
        }
    }

    @Test
    void connectionRefusalIsUnknownAndNotLocalFailure() throws Exception {
        int unboundPort;
        try (ServerSocket temporary = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unboundPort = temporary.getLocalPort();
        }
        try (SingleDeliveryHttpClient client = client(unboundPort)) {
            assertUnknown(client.postSimulatorOnce("evt-1", BODY, "secret", 500));
        }
    }

    @Test
    void rejectsTimeoutValuesOutsideTaskSnapshotRangeBeforeSending() throws Exception {
        try (LocalSocketReceiver receiver = new LocalSocketReceiver(Mode.DISCONNECT);
                SingleDeliveryHttpClient client = client(receiver.port())) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.postSimulatorOnce("evt-1", BODY, "secret", 0));
            assertThrows(IllegalArgumentException.class,
                    () -> client.postSimulatorOnce("evt-1", BODY, "secret", 15001));
            assertEquals(0, receiver.requestCount());
        }
    }

    private static void assertUnknown(SingleDeliveryHttpResult result) {
        assertEquals(UNKNOWN, result.classification().result());
        assertTrue(result.classification().retryable());
        assertNull(result.httpStatus());
        assertNull(result.responseExcerpt());
    }

    private static SingleDeliveryHttpClient client(int port) {
        SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                new MockEnvironment().withProperty("local.server.port", Integer.toString(port)));
        return new SingleDeliveryHttpClient(
                new MutableClock(Instant.parse("2026-10-29T00:00:00Z")),
                new DeliveryRequestSigner(), route);
    }

    private enum Mode {
        DISCONNECT, STALL_HEADERS, STALL_BODY, STALL_BODY_500, TRICKLE_HEADERS
    }

    private static final class LocalSocketReceiver implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final CountDownLatch received = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile Socket accepted;

        private LocalSocketReceiver(Mode mode) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            worker = Thread.ofVirtual().start(() -> receive(mode));
        }

        private int port() {
            return server.getLocalPort();
        }

        private int requestCount() {
            return requestCount.get();
        }

        private boolean awaitRequest() throws InterruptedException {
            return received.await(1, TimeUnit.SECONDS);
        }

        private void receive(Mode mode) {
            try (Socket socket = server.accept()) {
                accepted = socket;
                socket.setSoTimeout(2000);
                InputStream input = socket.getInputStream();
                int matching = 0;
                int headerBytes = 0;
                byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                while (matching < end.length && headerBytes++ < 8192) {
                    int next = input.read();
                    if (next < 0) {
                        return;
                    }
                    matching = next == end[matching] ? matching + 1 : (next == end[0] ? 1 : 0);
                }
                if (matching < end.length || input.readNBytes(BODY.length).length != BODY.length) {
                    return;
                }
                requestCount.incrementAndGet();
                received.countDown();
                if (mode == Mode.DISCONNECT) {
                    return;
                }
                if (mode == Mode.STALL_HEADERS) {
                    Thread.sleep(1000);
                    return;
                }
                OutputStream output = socket.getOutputStream();
                if (mode == Mode.TRICKLE_HEADERS) {
                    byte[] header = ("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\n"
                            + "Content-Type: text/plain\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    for (byte value : header) {
                        output.write(value);
                        output.flush();
                        Thread.sleep(20);
                    }
                    return;
                }
                output.write(("HTTP/1.1 " + (mode == Mode.STALL_BODY_500 ? "500 Error" : "200 OK")
                        + "\r\nContent-Length: 8192\r\n"
                        + "Content-Type: text/plain\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                Thread.sleep(1000);
            } catch (SocketException ignored) {
                // Cancellation closes the local socket while the worker is writing.
            } catch (IOException | InterruptedException ignored) {
                // Shutdown or a cancelled client request ends this test-only receiver.
            }
        }

        @Override
        public void close() throws Exception {
            worker.interrupt();
            if (accepted != null) {
                accepted.close();
            }
            server.close();
            worker.join(1000);
            assertTrue(!worker.isAlive(), "local receiver did not stop");
        }
    }
}
