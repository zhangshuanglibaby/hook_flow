package com.hookflow.delivery.api;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.DeliveryHistoryQueryService.Attempt;
import com.hookflow.delivery.application.DeliveryQueryService.Detail;
import com.hookflow.delivery.application.DeliveryQueryService.DeliveryPage;
import com.hookflow.delivery.application.DeliveryRedeliveryService.TaskSummary;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeliveryQueryViewTests {
    @Test void serializedSummaryAndDetailHaveOnlyContractFieldsAndExplicitNulls() {
        var mapper=JsonMapper.builder().build(); var now=Instant.parse("2026-10-30T03:40:00.123Z");
        var summary=new TaskSummary(5,"event","type",2,3,"PENDING",0,3,null,now);
        var page=mapper.readTree(mapper.writeValueAsString(new DeliveryPage(1,20,1,List.of(summary))));
        assertEquals(Set.of("page","size","total","items"),page.propertyNames());
        assertEquals(Set.of("taskId","eventId","eventType","endpointId","roundNo","status","attemptCount","maxAttempts","lastAttemptAt","createdAt"),page.path("items").get(0).propertyNames());
        assertTrue(page.path("items").get(0).path("lastAttemptAt").isNull());
        var started=new Attempt(1,"STARTED",now,null,null,null,null,null,null);
        var detail=mapper.readTree(mapper.writeValueAsString(new Detail(5,"event",3,"DELIVERING",1,3,null,now,null,List.of(started))));
        assertEquals(Set.of("taskId","eventId","roundNo","status","attemptCount","maxAttempts","nextRunAt","createdAt","finishedAt","attempts"),detail.propertyNames());
        assertEquals("2026-10-30T03:40:00.123Z",detail.path("createdAt").asString());
        var attempt=detail.path("attempts").get(0);
        assertEquals(Set.of("attemptNo","result","startedAt","endedAt","durationMs","httpStatus","responseExcerpt","errorCode","errorMessage"),attempt.propertyNames());
        for(String field:List.of("endedAt","durationMs","httpStatus","responseExcerpt","errorCode","errorMessage")) assertTrue(attempt.path(field).isNull());
    }

    @Test void diagnosticStringRepresentationsHideStoredResponseTextAndErrors() {
        var now=Instant.parse("2026-10-30T03:40:00.123Z");
        var attempt=new Attempt(1,"HTTP_FAILURE",now,now,0L,500,"private-excerpt","SAFE_CODE","safe stored error");
        var detail=new Detail(1,"event",1,"FINAL_FAILED",1,1,null,now,now,List.of(attempt));
        assertFalse(attempt.toString().contains("private-excerpt")); assertFalse(attempt.toString().contains("safe stored error"));
        assertFalse(detail.toString().contains("private-excerpt"));
    }
}
