package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PublicTargetAddressPolicyTests {
    @Test
    void rejectsProtectedIpv4Ranges() throws Exception {
        String[] blocked = {
                "0.0.0.1", "10.1.2.3", "100.64.0.1", "127.0.0.1",
                "169.254.1.2", "172.16.0.1", "172.31.255.255",
                "192.0.0.1", "192.0.2.1", "192.31.196.1",
                "192.52.193.1", "192.88.99.1", "192.168.1.2",
                "192.175.48.1", "198.18.0.1", "198.19.255.255",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"
        };
        for (String literal : blocked) {
            assertFalse(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName(literal)), literal);
        }
        assertFalse(PublicTargetAddressPolicy.isAllowed(null));
    }

    @Test
    void acceptsRepresentativeOrdinaryIpv4AddressesWithoutConnecting() throws Exception {
        for (String literal : new String[] {"1.1.1.1", "8.8.8.8", "100.63.255.255",
                "100.128.0.0", "172.32.0.1", "198.20.0.1"}) {
            assertTrue(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    void rejectsProtectedIpv6Ranges() throws Exception {
        String[] blocked = {
                "::", "::1", "fc00::1", "fd12::1", "fe80::1", "ff02::1",
                "2001::1", "2001:20::1", "2001:30::1", "2001:100::1",
                "2001:db8::1", "2001:1000::1", "2001:4e00::1",
                "2001:6000::1", "2002::1", "2003:4000::1", "2004::1",
                "2420::1", "2612::1", "2620:4f:8000::1", "2640::1",
                "2a20::1", "2b00::1", "2c10::1", "2d00::1",
                "3000::1", "3ffe::1", "3fff::1"
        };
        for (String literal : blocked) {
            assertFalse(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    void acceptsRepresentativeAllocatedIpv6RangesWithoutConnecting() throws Exception {
        for (String literal : new String[] {
                "2001:200::1", "2001:4c00::1", "2001:b000::1",
                "2001:2000::1", "2003::1",
                "2400::1", "2410::1", "2606:4700:4700::1111",
                "2610::1", "2620:1::1", "2630::1",
                "2800::1", "2a10::1", "2c00::1"
        }) {
            assertTrue(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    void appliesIpv4RulesToMappedIpv6Addresses() throws Exception {
        for (String literal : new String[] {"::ffff:127.0.0.1", "::ffff:10.1.2.3",
                "::ffff:169.254.1.2", "::ffff:192.168.1.2"}) {
            assertFalse(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName(literal)), literal);
        }
        assertTrue(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName("::ffff:8.8.8.8")));
        assertTrue(PublicTargetAddressPolicy.isAllowed(InetAddress.getByName("2606:4700:4700::1111")));

        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xff;
        mappedLoopback[11] = (byte) 0xff;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1;
        assertFalse(PublicTargetAddressPolicy.isAllowed(
                Inet6Address.getByAddress(null, mappedLoopback, -1)));
    }
}
