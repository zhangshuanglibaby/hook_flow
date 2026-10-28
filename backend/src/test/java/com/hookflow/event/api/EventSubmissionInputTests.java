package com.hookflow.event.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EventSubmissionInputTests {
    private final EventRequestJsonParser parser = new EventRequestJsonParser();

    @Test
    void acceptsExpectedFieldsAndPreservesExactIdentifiersAndPayload() {
        EventSubmissionInput input = validated("""
                {"eventId":" evt-1 ","eventType":"order.paid","endpointId":12,
                 "payload":{"b":1.0,"a":null}}
                """);

        assertEquals(" evt-1 ", input.eventId());
        assertEquals("order.paid", input.eventType());
        assertEquals(12L, input.endpointId());
        assertEquals("{\"a\":null,\"b\":1}", input.payload().json());
    }

    @Test
    void acceptsNullPayloadAndBoundaryUnicodeCodePointLengths() {
        String identifier = "😀".repeat(128);
        EventSubmissionInput input = validated("{\"eventId\":\"" + identifier
                + "\",\"eventType\":\"x\",\"endpointId\":9223372036854775807,\"payload\":null}");

        assertEquals(identifier, input.eventId());
        assertEquals(Long.MAX_VALUE, input.endpointId());
        assertEquals("null", input.payload().json());
    }

    @Test
    void rejectsUnknownAndMissingFields() {
        assertBadRequest("{\"eventId\":\"a\",\"eventType\":\"b\",\"endpointId\":1,"
                + "\"payload\":{},\"unexpected\":true}");
        assertBadRequest("{\"eventType\":\"b\",\"endpointId\":1,\"payload\":{}}");
        assertBadRequest("{\"eventId\":\"a\",\"endpointId\":1,\"payload\":{}}");
        assertBadRequest("{\"eventId\":\"a\",\"eventType\":\"b\",\"payload\":{}}");
        assertBadRequest(() -> EventSubmissionInput.from(null));
    }

    @Test
    void rejectsWrongStringTypesBlankValuesAndOverlongValues() {
        assertBadRequest(request("null", "\"b\"", "1"));
        assertBadRequest(request("123", "\"b\"", "1"));
        assertBadRequest(request("\"\"", "\"b\"", "1"));
        assertBadRequest(request("\" \\t \"", "\"b\"", "1"));
        assertBadRequest(request("\"" + "😀".repeat(129) + "\"", "\"b\"", "1"));
        assertBadRequest(request("\"a\"", "false", "1"));
        assertBadRequest(request("\"a\"", "\"  \"", "1"));
        assertBadRequest(request("\"a\"", "\"" + "x".repeat(129) + "\"", "1"));
    }

    @Test
    void rejectsNonPositiveNonIntegerAndOutOfRangeEndpointIds() {
        for (String id : new String[] {
                "0", "-1", "1.0", "1e0", "\"1\"", "null",
                "9223372036854775808", "-9223372036854775809"
        }) {
            assertBadRequest(request("\"a\"", "\"b\"", id));
        }
    }

    private EventSubmissionInput validated(String json) {
        return EventSubmissionInput.from(parser.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static String request(String id, String type, String endpointId) {
        return "{\"eventId\":" + id + ",\"eventType\":" + type
                + ",\"endpointId\":" + endpointId + ",\"payload\":[]}";
    }

    private void assertBadRequest(String json) {
        assertBadRequest(() -> validated(json));
    }

    private static void assertBadRequest(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(400, error.getStatusCode().value());
    }
}
