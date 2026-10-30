package com.hookflow.delivery.api;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.common.api.PageParameters;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DeliveryQueryInputTests {
    @Test void paginationDefaultsLimitsAndOverflowRemainBounded() {
        assertEquals(new PageParameters(1,20),PageParameters.parse(null,null));
        assertEquals(0,PageParameters.parse("1","1").offset());
        assertEquals(214748364600L,PageParameters.parse("2147483647","100").offset());
        for(String value:List.of("","0","-1","1.0","1e0","+1","01"," 1","2147483648","9223372036854775808")) {
            bad(()->PageParameters.parse(value,null)); bad(()->PageParameters.parse(null,value));
        }
        bad(()->PageParameters.parse(null,"101"));
    }

    @Test void onlySixStatusesAndPositiveLongIdsAreAccepted() {
        for(String status:List.of("PENDING","DELIVERING","WAIT_RETRY","PAUSED","SUCCESS","FINAL_FAILED"))
            assertEquals(status,DeliveryQueryInput.parse(status,null,"9223372036854775807",null,null).status());
        for(String status:List.of("","pending","Success","SUCCESS ","UNKNOWN","STARTED"))
            bad(()->DeliveryQueryInput.parse(status,null,null,null,null));
        assertEquals(1,DeliveryQueryInput.taskId("1")); assertEquals(Long.MAX_VALUE,DeliveryQueryInput.taskId("9223372036854775807"));
        for(String id:List.of("","0","-1","1.0","1e0","+1","01"," 1","9223372036854775808","18446744073709551616")) {
            bad(()->DeliveryQueryInput.taskId(id)); bad(()->DeliveryQueryInput.parse(null,null,id,null,null));
        }
        bad(()->DeliveryQueryInput.taskId(null));
    }

    @Test void eventTypeUsesCodePointsAndRetainsExactIdentity() {
        for(String value:List.of("A"," A ","é","é","😀".repeat(128),"%_\\' OR 1=1"))
            assertEquals(value,DeliveryQueryInput.parse(null,value,null,null,null).eventType());
        for(String value:List.of(""," \t\n","a".repeat(129),"😀".repeat(129)))
            bad(()->DeliveryQueryInput.parse(null,value,null,null,null));
    }

    @Test void strictUtcRangePreservesMillisAndSubMillisecondHalfOpenSemantics() {
        String start="2026-10-30T00:00:00.123Z",end="2026-10-30T00:00:00.124Z";
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.123"),DeliveryQueryInput.parse(null,null,null,start,null).from());
        assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.124"),DeliveryQueryInput.parse(null,null,null,null,end).to());
        var range=DeliveryQueryInput.parse(null,null,null,"2026-10-30T00:00:00.123000001Z","2026-10-30T00:00:00.123999999Z");
        assertEquals(range.from(),range.to()); assertEquals(LocalDateTime.parse("2026-10-30T00:00:00.124"),range.from());
        bad(()->DeliveryQueryInput.parse(null,null,null,start,start)); bad(()->DeliveryQueryInput.parse(null,null,null,end,start));
        for(String time:List.of("","2026-10-30","2026-10-30T00:00:00","2026-10-30T00:00:00+00:00",
                "2026-10-30T08:00:00+08:00","2026-10-30t00:00:00z","2026-02-30T00:00:00Z","2026-10-30T24:00:00Z",
                "2026-10-30T00:00:60Z","2026-10-30T00:00:00.1234567890Z","0000-01-01T00:00:00Z","9999-12-31T23:59:59.999999999Z")) {
            bad(()->DeliveryQueryInput.parse(null,null,null,time,null)); bad(()->DeliveryQueryInput.parse(null,null,null,null,time));
        }
    }

    static void bad(Runnable call) { assertEquals(400,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value()); }
}
