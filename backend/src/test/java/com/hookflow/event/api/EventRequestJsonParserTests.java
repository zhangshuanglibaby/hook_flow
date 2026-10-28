package com.hookflow.event.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EventRequestJsonParserTests {
    private final EventRequestJsonParser parser = new EventRequestJsonParser();

    @Test
    void returnsParsedRequestAndCanonicalPayloadWithoutReorderingArray() {
        var parsed = parser.parse(stream("""
                { "eventId":"demo", "payload":[{"z":1.0,"a":2},null] }
                """));

        assertEquals("demo", parsed.request().get("eventId").asString());
        assertEquals("[{\"a\":2,\"z\":1},null]", parsed.payload().json());
    }

    @Test
    void allowsJsonNullPayloadButRejectsMissingPayloadAndNonObjectRoot() {
        assertEquals("null", parser.parse(stream("{\"payload\":null}")).payload().json());
        assertBadRequest(stream("{}"));
        assertBadRequest(stream("null"));
        assertBadRequest(stream("[1]"));
        assertBadRequest(stream(""));
        assertBadRequest(null);
    }

    @Test
    void acceptsExactByteLimitAndRejectsOneExtraByte() {
        String prefix = "{\"payload\":\"";
        String suffix = "\"}";
        String atLimit = prefix + "x".repeat(EventRequestJsonParser.MAX_REQUEST_BYTES
                - prefix.length() - suffix.length()) + suffix;

        assertEquals(EventRequestJsonParser.MAX_REQUEST_BYTES,
                atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(parser.parse(stream(atLimit)).payload().json().startsWith("\"x"));
        assertBadRequest(stream(atLimit + " "));
    }

    @Test
    void countsUtf8BytesInsteadOfCharacters() {
        String valid = "{\"payload\":\"" + "中".repeat(87376) + "\"}";
        assertEquals(262142, valid.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(parser.parse(stream(valid)).payload().json().startsWith("\"中"));
        assertBadRequest(stream(valid.replaceFirst("中", "中中")));
    }

    @Test
    void rejectsMalformedJsonInvalidUtf8TrailingValueAndDuplicateKeys() {
        assertBadRequest(stream("{\"payload\":"));
        assertBadRequest(stream("{\"payload\":1} {}"));
        assertBadRequest(stream("{\"payload\":{\"x\":1,\"x\":2}}"));
        assertBadRequest(new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28}));
    }

    @Test
    void stopsReadingAfterLimitPlusOneAndMapsReadErrorsToBadRequest() {
        byte[] oversized = ("{\"payload\":\"" + "x".repeat(300000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(oversized);

        assertBadRequest(input);
        assertEquals(oversized.length - EventRequestJsonParser.MAX_REQUEST_BYTES - 1,
                input.available());

        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("test read error");
            }
        };
        assertBadRequest(broken);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private void assertBadRequest(InputStream input) {
        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, () -> parser.parse(input));
        assertEquals(400, error.getStatusCode().value());
    }
}
