package com.hookflow.delivery.infrastructure;

import static com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hookflow.support.MutableClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

// Run explicitly inside the test-only Linux image with --network none and --cap-add NET_ADMIN.
class SingleDeliveryTcpConnectTimeoutScenario {
    @Test
    void droppedLoopbackSynKeepsRealClientInTcpConnectPhaseUntilTimeout() throws Exception {
        assertEquals("1", System.getenv("HOOKFLOW_TCP_TIMEOUT_ISOLATED"));
        assertEquals("Linux", System.getProperty("os.name"));
        NetworkInterface loopback = NetworkInterface.getByName("lo");
        assertNotNull(loopback, "This scenario requires Linux loopback");
        assertTrue(loopback.isUp(), "Loopback must be up");
        for (NetworkInterface networkInterface : NetworkInterface.networkInterfaces().toList()) {
            assertTrue(!networkInterface.isUp() || networkInterface.isLoopback(),
                    "This scenario requires --network none: " + networkInterface.getName());
        }

        int port;
        try (ServerSocket temporary = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = temporary.getLocalPort();
        }

        iptables("-A", port);
        try {
            // A refused connection would fail immediately; this independently checks a real TCP connect timeout.
            try (Socket probe = new Socket()) {
                assertThrows(SocketTimeoutException.class,
                        () -> probe.connect(new InetSocketAddress("127.0.0.1", port), 300));
            }
            assertFalse(hasSynSentSocketTo(port), "Probe socket must be gone before the client starts");

            SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                    new MockEnvironment().withProperty("local.server.port", Integer.toString(port)));
            try (SingleDeliveryHttpClient client = new SingleDeliveryHttpClient(
                    new MutableClock(Instant.parse("2026-10-29T00:00:00Z")),
                    new DeliveryRequestSigner(), route)) {
                ExecutorService caller = Executors.newSingleThreadExecutor();
                Future<SingleDeliveryHttpResult> pending = caller.submit(() ->
                        client.postSimulatorOnce("evt-1", "{}".getBytes(StandardCharsets.UTF_8),
                                "secret", 4000));
                try {
                    long started = System.nanoTime();
                    long observationDeadline = started + TimeUnit.SECONDS.toNanos(3);
                    boolean connecting = false;
                    while (System.nanoTime() < observationDeadline && !pending.isDone()) {
                        if (hasSynSentSocketTo(port)) {
                            connecting = true;
                            break;
                        }
                        Thread.sleep(20);
                    }

                    assertTrue(connecting, "No SYN_SENT socket: cannot claim a TCP connect timeout; "
                            + "pending=" + pending.isDone() + "; tcp=" + Files.readString(Path.of("/proc/net/tcp"))
                            + "; tcp6=" + Files.readString(Path.of("/proc/net/tcp6")));
                    assertFalse(pending.isDone(), "Client finished before the TCP connect deadline");
                    SingleDeliveryHttpResult result = pending.get(8, TimeUnit.SECONDS);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) >= 0, elapsed.toString());
                    assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, elapsed.toString());
                    assertEquals(UNKNOWN, result.classification().result());
                    assertTrue(result.classification().retryable());
                    assertNull(result.httpStatus());
                    assertNull(result.responseExcerpt());
                } finally {
                    pending.cancel(true);
                    caller.shutdownNow();
                    assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS),
                            "Caller did not stop after cancellation");
                }
            }
        } finally {
            iptables("-D", port);
        }
    }

    private static boolean hasSynSentSocketTo(int port) throws Exception {
        String destination = "0100007F:%04X".formatted(port).toUpperCase(Locale.ROOT);
        for (Path table : List.of(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"))) {
            for (String line : Files.readAllLines(table)) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length > 3 && columns[2].endsWith(destination)
                        && "02".equals(columns[3])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void iptables(String action, int port) throws Exception {
        Process process = new ProcessBuilder("iptables", "-w", action, "OUTPUT",
                "-o", "lo", "-d", "127.0.0.1/32", "-p", "tcp",
                "--dport", Integer.toString(port), "--tcp-flags", "SYN", "SYN",
                "-j", "DROP").redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            fail("iptables did not finish");
        }
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
