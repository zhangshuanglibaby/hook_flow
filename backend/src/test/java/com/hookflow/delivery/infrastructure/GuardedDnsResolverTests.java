package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

class GuardedDnsResolverTests {
    @Test
    void rejectsEntireResultWhenAnyCandidateIsProtected() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        GuardedDnsResolver resolver = new GuardedDnsResolver(
                resolverReturning(new InetAddress[] {publicAddress, loopback}));

        assertInstanceOf(GuardedDnsResolver.ProtectedTargetAddressException.class,
                assertThrows(UnknownHostException.class, () -> resolver.resolve("target.example", 443)));
    }

    @Test
    void bindsOnlyCheckedAddressesToTheConnectionPort() throws Exception {
        InetAddress first = InetAddress.getByName("1.1.1.1");
        InetAddress second = InetAddress.getByName("8.8.8.8");
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver delegate = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                resolutions.incrementAndGet();
                return new InetAddress[] {first, second};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };

        List<InetSocketAddress> destinations = new GuardedDnsResolver(delegate)
                .resolve("target.example", 8443);
        assertEquals(List.of(new InetSocketAddress(first, 8443),
                new InetSocketAddress(second, 8443)), destinations);
        assertEquals(1, resolutions.get());
    }

    @Test
    void rejectsDnsRebindingOnTheNextResolution() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver delegate = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return resolutions.incrementAndGet() == 1
                        ? new InetAddress[] {InetAddress.getByName("8.8.8.8")}
                        : new InetAddress[] {InetAddress.getByName("192.168.1.2")};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        GuardedDnsResolver resolver = new GuardedDnsResolver(delegate);

        assertEquals(1, resolver.resolve("target.example", 443).size());
        assertThrows(UnknownHostException.class, () -> resolver.resolve("target.example", 443));
        assertEquals(2, resolutions.get());
    }

    @Test
    void rejectsMissingOrEmptyResults() {
        for (InetAddress[] result : new InetAddress[][] {null, new InetAddress[0]}) {
            GuardedDnsResolver resolver = new GuardedDnsResolver(resolverReturning(result));
            assertFalse(GuardedDnsResolver.ProtectedTargetAddressException.class.isInstance(
                    assertThrows(UnknownHostException.class, () -> resolver.resolve("target.example", 443))));
        }
    }

    @Test
    void keepsOrdinaryDnsFailureDistinctFromProtectedAddress() {
        DnsResolver failing = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                throw new UnknownHostException("ordinary DNS failure");
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };

        UnknownHostException failure = assertThrows(UnknownHostException.class,
                () -> new GuardedDnsResolver(failing).resolve("target.example", 443));
        assertFalse(GuardedDnsResolver.ProtectedTargetAddressException.class.isInstance(failure));
        assertEquals("ordinary DNS failure", failure.getMessage());
    }

    private static DnsResolver resolverReturning(InetAddress[] result) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return result;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }
}
