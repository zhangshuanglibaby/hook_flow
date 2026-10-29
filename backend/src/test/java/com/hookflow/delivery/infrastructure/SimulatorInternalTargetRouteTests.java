package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SimulatorInternalTargetRouteTests {
    @Test
    void usesActualLocalPortAndFixedLoopbackPath() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "0")
                .withProperty("local.server.port", "32145")
                .withProperty("hookflow.simulator.url", "https://external.example/receive");

        URI target = new SimulatorInternalTargetRoute(environment).targetUri();

        assertEquals(URI.create("http://127.0.0.1:32145"
                + SimulatorInternalTargetRoute.PATH), target);
    }

    @Test
    void usesConfiguredOrDefaultPortWhenThereIsNoRandomLocalPort() {
        assertEquals(URI.create("http://127.0.0.1:8080"
                        + SimulatorInternalTargetRoute.PATH),
                new SimulatorInternalTargetRoute(new MockEnvironment()).targetUri());
        assertEquals(URI.create("http://127.0.0.1:9090"
                        + SimulatorInternalTargetRoute.PATH),
                new SimulatorInternalTargetRoute(
                        new MockEnvironment().withProperty("server.port", "9090")).targetUri());
    }

    @Test
    void refusesAnUnresolvedOrInvalidListeningPort() {
        for (String port : new String[] {"0", "-1", "65536"}) {
            SimulatorInternalTargetRoute route = new SimulatorInternalTargetRoute(
                    new MockEnvironment().withProperty("server.port", port));
            assertThrows(IllegalStateException.class, route::targetUri, port);
        }
    }
}
