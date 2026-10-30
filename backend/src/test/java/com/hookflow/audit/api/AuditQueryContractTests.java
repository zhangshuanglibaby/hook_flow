package com.hookflow.audit.api;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.audit.application.AuditQueryService.AuditPage;
import com.hookflow.audit.application.AuditQueryService.Entry;
import com.hookflow.common.api.PageParameters;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class AuditQueryContractTests {
    @Test void sharedPaginationKeepsDefaultsBoundsAndLongOffset() {
        assertEquals(new PageParameters(1, 20), PageParameters.parse(null, null));
        assertEquals(new PageParameters(1, 1), PageParameters.parse("1", "1"));
        assertEquals(214_748_364_600L, PageParameters.parse("2147483647", "100").offset());
        for (String value : List.of("0", "-1", "", " ", "1.0", "1e0", "01", "+1", "NaN", "2147483648", "9223372036854775808")) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> PageParameters.parse(value, null)).getStatusCode().value());
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> PageParameters.parse(null, value)).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> PageParameters.parse(null, "101"));
    }

    @Test void pageHasExactlySafeFieldsLongNumbersUtcAndOriginalOperationRequestId() {
        var entry = new Entry(Long.MAX_VALUE, 3_000_000_000L, "EVENT_REDELIVERED", "EVENT", "9000000000",
                Instant.parse("2026-10-30T04:45:00.123Z"), "0287643f-3fa8-4e11-b103-4b9312814f41");
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(new AuditPage(1, 20, 9_000_000_000L, List.of(entry))));
        assertEquals(Set.of("page", "size", "total", "items"), json.propertyNames());
        assertEquals(9_000_000_000L, json.path("total").asLong());
        var item = json.path("items").get(0);
        assertEquals(Set.of("id", "actorUserId", "action", "objectType", "objectId", "occurredAt", "requestId"), item.propertyNames());
        assertEquals(Long.MAX_VALUE, item.path("id").asLong()); assertEquals(3_000_000_000L, item.path("actorUserId").asLong());
        assertTrue(item.path("objectId").isString()); assertEquals("9000000000", item.path("objectId").asString());
        assertEquals(entry.occurredAt(), Instant.parse(item.path("occurredAt").asString()));
        assertTrue(item.path("occurredAt").asString().endsWith("Z")); assertEquals(entry.requestId(), item.path("requestId").asString());
        var empty = mapper.readTree(mapper.writeValueAsString(new AuditPage(1, 20, 0, List.of())));
        assertEquals(0, empty.path("items").size()); assertEquals(0, empty.path("total").asLong());
    }
}
