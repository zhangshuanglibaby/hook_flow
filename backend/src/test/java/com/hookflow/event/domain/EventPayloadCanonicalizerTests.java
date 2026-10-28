package com.hookflow.event.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class EventPayloadCanonicalizerTests {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();
    private final EventPayloadCanonicalizer canonicalizer = new EventPayloadCanonicalizer();

    @Test
    void recursivelySortsObjectKeysAndIgnoresWhitespace() {
        CanonicalEventPayload first = canonicalize("""
                { "z": [ { "y": true, "a": null }, 3 ], "a": { "b": "文字", "a": 1 } }
                """);
        CanonicalEventPayload second = canonicalize("""
                {"a":{"a":1,"b":"文字"},"z":[{"a":null,"y":true},3]}
                """);

        assertEquals("{\"a\":{\"a\":1,\"b\":\"文字\"},\"z\":[{\"a\":null,\"y\":true},3]}",
                first.json());
        assertEquals(first.json(), second.json());
        assertArrayEquals(first.fingerprint(), second.fingerprint());
        assertTrue(first.sameContent(second));
    }

    @Test
    void normalizesExactNumericValuesWithoutFloatingPointRounding() {
        CanonicalEventPayload first = canonicalize("""
                [1,1.0,1e0,0.0100,1000,123456789012345678901234567890.1000]
                """);
        CanonicalEventPayload second = canonicalize("""
                [1.00,1E+0,1,1e-2,1e3,123456789012345678901234567890.1]
                """);

        assertEquals(first.json(), second.json());
        assertTrue(first.sameContent(second));
    }

    @Test
    void preservesArrayOrderAndDistinguishesDifferentJsonValues() {
        CanonicalEventPayload ordered = canonicalize("[1,{\"b\":2,\"a\":3}]");
        CanonicalEventPayload reversed = canonicalize("[{\"a\":3,\"b\":2},1]");
        CanonicalEventPayload changed = canonicalize("[2,{\"b\":2,\"a\":3}]");

        assertFalse(ordered.sameContent(reversed));
        assertFalse(ordered.sameContent(changed));
        assertEquals("null", canonicalize("null").json());
        assertFalse(canonicalize("1").sameContent(canonicalize("\"1\"")));
    }

    @Test
    void hashesUtf8CanonicalTextAndDoesNotExposeMutableFingerprint() throws NoSuchAlgorithmException {
        CanonicalEventPayload payload = canonicalize("{\"z\":\"中文\",\"a\":1.0}");
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(payload.json().getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(expected, payload.fingerprint());
        byte[] leaked = payload.fingerprint();
        leaked[0] ^= 1;
        assertArrayEquals(expected, payload.fingerprint());
    }

    @Test
    void equalFingerprintAloneDoesNotMakeContentsEqual() {
        CanonicalEventPayload original = canonicalize("{\"a\":1}");
        CanonicalEventPayload simulatedCollision =
                new CanonicalEventPayload("{\"a\":2}", original.fingerprint());

        assertFalse(original.sameContent(simulatedCollision));
        assertFalse(original.sameContent(null));
    }

    private CanonicalEventPayload canonicalize(String json) {
        return canonicalizer.canonicalize(mapper.readTree(json));
    }
}
