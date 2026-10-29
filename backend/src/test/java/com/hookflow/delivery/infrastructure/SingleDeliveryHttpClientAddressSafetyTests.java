package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hookflow.support.MutableClock;
import com.hookflow.support.TestHttpReceiver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SingleDeliveryHttpClientAddressSafetyTests {
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final Duration NO_REQUEST = Duration.ofMillis(150);

    @Test
    void blocksResolvedIpv4AndIpv6ProtectedAddressesBeforeAnyHttpRequest() throws Exception {
        for (String literal : new String[] {
                "127.0.0.1", "10.1.2.3", "192.168.1.2", "169.254.1.2",
                "::1", "fd00::1", "fe80::1", "2004::1", "3ffe::1",
                "::ffff:127.0.0.1"
        }) {
            try (TestHttpReceiver receiver = TestHttpReceiver.start()) {
                AtomicInteger resolutions = new AtomicInteger();
                GuardedDnsResolver guarded = new GuardedDnsResolver(
                        returning(new InetAddress[] {InetAddress.getByName(literal)}, resolutions));
                try (SingleDeliveryHttpClient client = client(receiver, guarded)) {
                    assertThrows(UnknownHostException.class,
                            () -> client.postOnce(target(receiver), "evt-1", BODY, "test-secret"), literal);
                    assertEquals(1, resolutions.get(), literal);
                    assertNull(receiver.awaitRequest(NO_REQUEST), literal);
                }
            }
        }
    }

    @Test
    void rejectsMixedCandidatesWithoutConnectingToTheProtectedOne() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start()) {
            GuardedDnsResolver guarded = new GuardedDnsResolver(returning(new InetAddress[] {
                    InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")
            }, new AtomicInteger()));
            try (SingleDeliveryHttpClient client = client(receiver, guarded)) {
                assertThrows(UnknownHostException.class,
                        () -> client.postOnce(target(receiver), "evt-1", BODY, "test-secret"));
                assertNull(receiver.awaitRequest(NO_REQUEST));
            }
        }
    }

    @Test
    void checksAgainAtConnectionAfterAnEarlierPublicResolutionRebindsToLoopback() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start()) {
            AtomicInteger resolutions = new AtomicInteger();
            DnsResolver changing = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    return resolutions.incrementAndGet() == 1
                            ? new InetAddress[] {InetAddress.getByName("8.8.8.8")}
                            : new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            };
            GuardedDnsResolver guarded = new GuardedDnsResolver(changing);
            assertEquals(1, guarded.resolve("target.invalid", receiver.uri().getPort()).size());
            try (SingleDeliveryHttpClient client = client(receiver, guarded)) {
                assertThrows(UnknownHostException.class,
                        () -> client.postOnce(target(receiver), "evt-1", BODY, "test-secret"));
                assertEquals(2, resolutions.get());
                assertNull(receiver.awaitRequest(NO_REQUEST));
            }
        }
    }

    @Test
    void rejectsExternalHttpEvenThoughSimulatorUsesInternalHttp() throws Exception {
        try (TestHttpReceiver receiver = TestHttpReceiver.start();
                SingleDeliveryHttpClient client = client(receiver, new GuardedDnsResolver())) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.postOnce(receiver.simulatorUri().toString(), "evt-1", BODY, "test-secret"));
            assertNull(receiver.awaitRequest(NO_REQUEST));
        }
    }

    @Test
    void ipLiteralTargetsAlsoPassThroughTheGuardedConnectionResolver() throws Exception {
        for (String literal : new String[] {"127.0.0.1", "[::1]"}) {
            try (TestHttpReceiver receiver = TestHttpReceiver.start()) {
                AtomicInteger resolutions = new AtomicInteger();
                GuardedDnsResolver guarded = new GuardedDnsResolver(returning(
                        new InetAddress[] {InetAddress.getByName("127.0.0.1")}, resolutions));
                try (SingleDeliveryHttpClient client = client(receiver, guarded)) {
                    String target = "https://" + literal + ":" + receiver.uri().getPort() + "/receive";
                    assertThrows(UnknownHostException.class,
                            () -> client.postOnce(target, "evt-1", BODY, "test-secret"));
                    assertEquals(1, resolutions.get(), literal);
                    assertNull(receiver.awaitRequest(NO_REQUEST), literal);
                }
            }
        }
    }

    @Test
    void ignoresJvmHttpAndSocksProxyPropertiesAndStillRejectsTheResolvedTarget() throws Exception {
        String previousHost = System.getProperty("https.proxyHost");
        String previousPort = System.getProperty("https.proxyPort");
        String previousSocksHost = System.getProperty("socksProxyHost");
        String previousSocksPort = System.getProperty("socksProxyPort");
        try (TestHttpReceiver receiver = TestHttpReceiver.start()) {
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", Integer.toString(receiver.uri().getPort()));
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", "1");
            AtomicInteger resolutions = new AtomicInteger();
            GuardedDnsResolver guarded = new GuardedDnsResolver(returning(
                    new InetAddress[] {InetAddress.getByName("127.0.0.1")}, resolutions));
            try (SingleDeliveryHttpClient client = client(receiver, guarded)) {
                assertThrows(UnknownHostException.class,
                        () -> client.postOnce(target(receiver), "evt-1", BODY, "test-secret"));
                assertEquals(1, resolutions.get());
                assertNull(receiver.awaitRequest(NO_REQUEST));
            }
        } finally {
            restoreProperty("https.proxyHost", previousHost);
            restoreProperty("https.proxyPort", previousPort);
            restoreProperty("socksProxyHost", previousSocksHost);
            restoreProperty("socksProxyPort", previousSocksPort);
        }
    }

    private static SingleDeliveryHttpClient client(TestHttpReceiver receiver,
            GuardedDnsResolver guarded) {
        SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                new MockEnvironment().withProperty("local.server.port",
                        Integer.toString(receiver.uri().getPort())));
        return new SingleDeliveryHttpClient(
                new MutableClock(Instant.parse("2026-10-29T00:00:00Z")),
                new DeliveryRequestSigner(), route, guarded);
    }

    private static String target(TestHttpReceiver receiver) {
        return "https://target.invalid:" + receiver.uri().getPort() + "/receive";
    }

    private static DnsResolver returning(InetAddress[] addresses, AtomicInteger resolutions) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                resolutions.incrementAndGet();
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
