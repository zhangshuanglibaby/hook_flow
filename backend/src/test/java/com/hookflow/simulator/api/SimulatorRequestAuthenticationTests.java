package com.hookflow.simulator.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hookflow.delivery.application.SimulatorDeliverySignatureAccess;
import com.hookflow.delivery.infrastructure.DeliveryRequestSigner;
import com.hookflow.delivery.infrastructure.SimulatorSignatureSnapshotRepository;
import com.hookflow.endpoint.application.EndpointQueryService;
import com.hookflow.endpoint.application.EndpointSnapshotSecretAccess;
import com.hookflow.endpoint.domain.EndpointConfiguration;
import com.hookflow.endpoint.domain.EndpointTargetType;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import com.hookflow.event.application.SimulatorEventIdentity;
import com.hookflow.simulator.application.SimulatorRequestAuthentication;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class SimulatorRequestAuthenticationTests {
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final byte[] BODY = "{\"message\":\"你好\",\"value\":1}".getBytes(StandardCharsets.UTF_8);
    private final DeliveryRequestSigner signer = new DeliveryRequestSigner();
    private final EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final SimulatorSignatureSnapshotRepository snapshots = mock(SimulatorSignatureSnapshotRepository.class);
    private final SimulatorEventIdentity events = mock(SimulatorEventIdentity.class);
    private final EndpointQueryService endpoints = mock(EndpointQueryService.class);
    private String secret;
    private SimulatorRequestAuthentication authentication;

    @BeforeEach
    void prepare() {
        secret = cipher.generateSecret();
        byte[] ciphertext = cipher.encrypt(secret);
        when(events.find(anyString())).thenReturn(Optional.of(new SimulatorEventIdentity.Identity(1, 2, 1)));
        when(endpoints.findById(2)).thenReturn(Optional.of(new EndpointConfiguration(2, "simulator",
                EndpointTargetType.SIMULATOR, null, 5000, true, NOW, NOW)));
        when(snapshots.matches(eq(1L), eq(1), any())).thenAnswer(invocation -> {
            Predicate<byte[]> verify = invocation.getArgument(2);
            return verify.test(ciphertext);
        });
        authentication = authenticator(new EndpointSnapshotSecretAccess(cipher));
    }

    private SimulatorRequestAuthentication authenticator(EndpointSnapshotSecretAccess secrets) {
        return new SimulatorRequestAuthentication(Clock.fixed(NOW, ZoneOffset.UTC),
                new SimulatorDeliverySignatureAccess(events, endpoints, snapshots, secrets, signer));
    }

    @Test
    void fixedVectorAgreesWithFormalSenderAndVerifier() {
        EndpointSnapshotSecretAccess vectorSecret = mock(EndpointSnapshotSecretAccess.class);
        when(vectorSecret.decryptForSimulatorVerification(any())).thenReturn("hookflow-test-key");
        String timestamp = "2026-10-29T00:00:00Z";
        var access = new SimulatorDeliverySignatureAccess(events, endpoints, snapshots, vectorSecret, signer);
        var fixed = new SimulatorRequestAuthentication(Clock.fixed(Instant.parse(timestamp), ZoneOffset.UTC), access);
        String vector = "710bdb35ae3f6919f44f5847235254c1273fe1c258313c1dabf1d4329945e938";
        assertEquals(vector, signer.sign("evt-001", timestamp, BODY, "hookflow-test-key"));
        assertDoesNotThrow(() -> fixed.authenticate("evt-001", timestamp, vector, BODY));
    }

    @Test
    void realCipherAndHmacAuthenticateOriginalBytesOnly() {
        String timestamp = NOW.toString();
        String signature = signer.sign("evt-001", timestamp, BODY, secret);
        assertDoesNotThrow(() -> authentication.authenticate("evt-001", timestamp, signature, BODY));
        rejected("evt-002", timestamp, signature, BODY);
        rejected("evt-001", NOW.plusSeconds(1).toString(), signature, BODY);
        rejected("evt-001", timestamp, signature, "{\"value\":1,\"message\":\"你好\"}".getBytes(StandardCharsets.UTF_8));
        rejected("evt-001", timestamp, signature, "{\"message\":\"你好\",\"value\":1.0}".getBytes(StandardCharsets.UTF_8));
        rejected("evt-001", timestamp, signature, " {\"message\":\"你好\",\"value\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void exactPastAndFutureWindowBoundariesAreInclusiveAtNanosecondPrecision() {
        for (Instant instant : new Instant[] {NOW.minusSeconds(300), NOW.plusSeconds(300), NOW}) {
            String timestamp = instant.toString();
            assertDoesNotThrow(() -> authentication.authenticate("evt-001", timestamp,
                    signer.sign("evt-001", timestamp, BODY, secret), BODY));
        }
        for (Instant instant : new Instant[] {NOW.minusSeconds(300).minusNanos(1), NOW.plusSeconds(300).plusNanos(1)}) {
            String timestamp = instant.toString();
            rejected("evt-001", timestamp, signer.sign("evt-001", timestamp, BODY, secret), BODY);
        }
    }

    @Test
    void rejectsNonCanonicalOrMalformedTimestampAndSignatureWithoutSnapshotRead() {
        for (String timestamp : new String[] {"", " ", "1761782400", "2026-10-30T00:00:00+00:00",
                "2026-10-30T00:00:00.0Z", "2026-10-30t00:00:00z", "2026-10-30T00:00:60Z", "x".repeat(41)}) {
            rejected("evt-001", timestamp, "0".repeat(64), BODY);
        }
        for (String signature : new String[] {"", " ", "0".repeat(63), "0".repeat(65), "A".repeat(64),
                "g".repeat(64), "0x" + "0".repeat(64), "0".repeat(64) + "," + "0".repeat(64)}) {
            rejected("evt-001", NOW.toString(), signature, BODY);
        }
        rejected(null, NOW.toString(), "0".repeat(64), BODY);
        rejected(" ", NOW.toString(), "0".repeat(64), BODY);
        rejected("x".repeat(129), NOW.toString(), "0".repeat(64), BODY);
        rejected("a\nb", NOW.toString(), "0".repeat(64), BODY);
        rejected("evt-001", null, "0".repeat(64), BODY);
        rejected("evt-001", NOW.toString(), null, BODY);
        verifyNoInteractions(snapshots);
    }

    @Test
    void bodyReadHasExactLimitAndReadsOnlyOneExtraByte() throws Exception {
        byte[] exact = new byte[SimulatorReceiveController.MAX_BODY_BYTES];
        assertArrayEquals(exact, SimulatorReceiveController.readBody(new ByteArrayInputStream(exact)));
        ByteArrayInputStream oversized = new ByteArrayInputStream(new byte[exact.length + 100]);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> SimulatorReceiveController.readBody(oversized)).getStatusCode().value());
        assertEquals(99, oversized.available());
        assertThrows(ResponseStatusException.class,
                () -> SimulatorReceiveController.readBody(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void missingBlankDuplicateAndOverlongHeadersNeverEnterAcknowledgement() {
        var acknowledgement = mock(SimulatorVerifiedReception.class);
        var controller = new SimulatorReceiveController(authentication, acknowledgement);
        String[] names = {"X-HookFlow-Event-Id", "X-HookFlow-Timestamp", "X-HookFlow-Signature"};
        for (String name : names) {
            for (String mode : new String[] {"missing", "blank", "duplicate", "long", "comma"}) {
                MockHttpServletRequest request = validRequest();
                if (mode.equals("missing")) request.removeHeader(name);
                if (mode.equals("blank")) { request.removeHeader(name); request.addHeader(name, " "); }
                if (mode.equals("duplicate")) request.addHeader(name, request.getHeader(name));
                if (mode.equals("long")) { request.removeHeader(name); request.addHeader(name, "x".repeat(300)); }
                if (mode.equals("comma")) { request.removeHeader(name); request.addHeader(name, "bad,bad"); }
                assertThrows(ResponseStatusException.class, () -> controller.receive(request), name + ":" + mode);
            }
        }
        verifyNoInteractions(acknowledgement);
    }

    private MockHttpServletRequest validRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-HookFlow-Event-Id", "evt-001");
        request.addHeader("X-HookFlow-Timestamp", NOW.toString());
        request.addHeader("X-HookFlow-Signature", signer.sign("evt-001", NOW.toString(), BODY, secret));
        request.setContent(BODY);
        return request;
    }

    private void rejected(String eventId, String timestamp, String signature, byte[] body) {
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> authentication.authenticate(eventId, timestamp, signature, body)).getStatusCode().value());
    }
}
