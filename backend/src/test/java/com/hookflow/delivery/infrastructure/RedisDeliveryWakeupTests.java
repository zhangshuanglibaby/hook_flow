package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hookflow.delivery.application.DueDeliveryScanner;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RedisDeliveryWakeupTests {
    @Test
    void slowRedisNeverRunsOnCallerAndRepeatedRequestsStayBounded() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread sink = new Thread(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            sink.setDaemon(true);
            sink.start();
            DueDeliveryScanner scanner = mock(DueDeliveryScanner.class);
            try (var wakeup = new RedisDeliveryWakeup(true, "127.0.0.1", server.getLocalPort(), "", "", scanner)) {
                wakeup.requestPublish();
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                assertTimeout(Duration.ofMillis(300), () -> {
                    for (int i = 0; i < 10000; i++) wakeup.requestPublish();
                });
                assertEquals(1, Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive() && t.getName().equals("hookflow-redis-publish")).count());
                verifyNoInteractions(scanner);
            } finally {
                release.countDown();
                sink.join(2000);
            }
        }
    }

    @Test
    void disabledOrClosedWakeupStartsNoConnectionsOrScans() {
        DueDeliveryScanner scanner = mock(DueDeliveryScanner.class);
        try (var wakeup = new RedisDeliveryWakeup(false, "127.0.0.1", 1, "", "", scanner)) {
            wakeup.start();
            wakeup.requestPublish();
        }
        var closed = new RedisDeliveryWakeup(true, "127.0.0.1", 1, "", "", scanner);
        closed.close();
        assertDoesNotThrow(() -> {
            closed.start();
            closed.requestPublish();
            closed.close();
        });
        verifyNoInteractions(scanner);
    }
}
