package com.hookflow.delivery.api;

import com.hookflow.config.RequestIdFilter;
import com.hookflow.delivery.application.DeliveryRedeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile("!skeleton")
@RestController
public class DeliveryRedeliveryController {
    private final DeliveryRedeliveryService redelivery;

    public DeliveryRedeliveryController(DeliveryRedeliveryService redelivery) {
        this.redelivery = redelivery;
    }

    @PostMapping(path = "/api/v1/admin/events/{eventId}/redeliver", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeliveryRedeliveryService.TaskSummary> redeliver(@PathVariable String eventId,
            @RequestBody(required = false) byte[] body, Principal principal, HttpServletRequest request) {
        if (body != null && body.length != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.accepted().body(redelivery.redeliver(eventId,
                Long.parseLong(principal.getName()),
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
    }
}
