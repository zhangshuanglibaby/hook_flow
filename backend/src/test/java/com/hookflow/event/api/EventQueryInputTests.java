package com.hookflow.event.api;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.common.api.PageParameters;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EventQueryInputTests {
    @Test void sharedPaginationDefaultsBoundsAndOverflowAreBounded() {
        assertEquals(new PageParameters(1,20), PageParameters.parse(null,null));
        assertEquals(0,PageParameters.parse("1","1").offset());
        assertEquals(214748364600L,PageParameters.parse("2147483647","100").offset());
        for (String value : List.of("", "0", "-1", "1.0", "1e0", " 1", "+1", "01", "2147483648", "9223372036854775808")) {
            bad(() -> PageParameters.parse(value,null)); bad(() -> PageParameters.parse(null,value));
        }
        bad(() -> PageParameters.parse(null,"101"));
    }

    @Test void onlySixExactStatusesAndPositiveLongEndpointAreAccepted() {
        for (String status : List.of("PENDING","DELIVERING","WAIT_RETRY","PAUSED","SUCCESS","FINAL_FAILED"))
            assertEquals(status,parse(null,null,status,"9223372036854775807",null,null).status());
        for (String status : List.of("", "pending", "Success", "UNKNOWN", "STARTED", "SUCCESS "))
            bad(() -> parse(null,null,status,null,null,null));
        for (String id : List.of("", "0", "-1", "1.0", "1e0", " 1", "+1", "01", "9223372036854775808", "18446744073709551616"))
            bad(() -> parse(null,null,null,id,null,null));
        assertEquals(1L,parse(null,null,null,"1",null,null).endpointId());
    }

    @Test void stringsReuseSubmissionCodePointRulesWithoutTrimmingFoldingOrWildcardInterpretation() {
        for (String value : List.of(" ID ","Order.Paid","%_\\' OR 1=1", "😀".repeat(128),"é","é")) {
            assertEquals(value,EventQueryInput.eventId(value));
            var input=parse(value,value,null,null,null,null);
            assertEquals(value,input.eventId()); assertEquals(value,input.eventType());
        }
        for (String value : List.of("", " \t\n", "a".repeat(129), "😀".repeat(129))) {
            bad(() -> EventQueryInput.eventId(value));
            bad(() -> parse(value,null,null,null,null,null));
            bad(() -> parse(null,value,null,null,null,null));
        }
        bad(() -> EventQueryInput.eventId(null));
    }

    @Test void strictUtcHalfOpenBoundsAcceptSingleEndsAndPreserveSubMillisecondMeaning() {
        String start="2026-10-30T00:00:00.123Z", end="2026-10-30T00:00:00.124Z";
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.123"),parse(null,null,null,null,start,null).from());
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.124"),parse(null,null,null,null,null,end).to());
        var nanos=parse(null,null,null,null,"2026-10-30T00:00:00.123000001Z","2026-10-30T00:00:00.123999999Z");
        assertEquals(nanos.from(),nanos.to()); assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.124"),nanos.from());
        bad(() -> parse(null,null,null,null,start,start)); bad(() -> parse(null,null,null,null,end,start));
        for (String value : List.of("", "2026-10-30", "2026-10-30T00:00:00", "2026-10-30T00:00:00+00:00",
                "2026-10-30T08:00:00+08:00", "2026-10-30t00:00:00z", "2026-02-30T00:00:00Z",
                "2026-10-30T24:00:00Z", "2026-10-30T00:00:60Z", "2026-10-30T00:00:00.1234567890Z",
                "0000-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z")) {
            bad(() -> parse(null,null,null,null,value,null)); bad(() -> parse(null,null,null,null,null,value));
        }
    }

    private static com.hookflow.event.application.EventQueryService.Filter parse(String id,String type,String status,String endpoint,String from,String to) {
        return EventQueryInput.parse(id,type,status,endpoint,from,to);
    }
    private static void bad(Runnable call) { assertEquals(400,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value()); }
}
