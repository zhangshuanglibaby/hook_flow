package com.hookflow.delivery.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeliveryRequestSignerTests {
    private final DeliveryRequestSigner signer = new DeliveryRequestSigner();

    @Test
    void signsExactUtf8PrefixAndRawBodyAsLowercaseHex() {
        byte[] body = "{\"message\":\"你好\",\"value\":1}".getBytes(StandardCharsets.UTF_8);

        assertEquals("710bdb35ae3f6919f44f5847235254c1273fe1c258313c1dabf1d4329945e938", signer.sign(
                "evt-001", "2026-10-29T00:00:00Z", body, "hookflow-test-key"));
    }

    @Test
    void changingAnyInputChangesTheSignature() {
        byte[] body = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        String baseline = signer.sign("evt-001", "2026-10-29T00:00:00Z", body, "key");

        assertNotEquals(baseline, signer.sign("evt-002", "2026-10-29T00:00:00Z", body, "key"));
        assertNotEquals(baseline, signer.sign("evt-001", "2026-10-29T00:00:01Z", body, "key"));
        assertNotEquals(baseline, signer.sign("evt-001", "2026-10-29T00:00:00Z",
                "{\"value\":1.0}".getBytes(StandardCharsets.UTF_8), "key"));
        assertNotEquals(baseline, signer.sign("evt-001", "2026-10-29T00:00:00Z", body, "other-key"));
        assertEquals(baseline, signer.sign("evt-001", "2026-10-29T00:00:00Z", body, "key"));
    }

    @Test
    void rejectsMissingInputsWithoutExposingTheSecret() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThrows(NullPointerException.class, () -> signer.sign(null, "t", body, "secret"));
        assertThrows(NullPointerException.class, () -> signer.sign("event", null, body, "secret"));
        assertThrows(NullPointerException.class, () -> signer.sign("event", "t", null, "secret"));
        assertThrows(NullPointerException.class, () -> signer.sign("event", "t", body, null));
        assertEquals("签名密钥缺失",
                assertThrows(IllegalArgumentException.class,
                        () -> signer.sign("event", "t", body, "")).getMessage());
    }
}
