package com.hookflow.simulator.api;

import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.simulator.application.SimulatorRequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile("!skeleton")
@RestController
public class SimulatorReceiveController {
    public static final int MAX_BODY_BYTES = 256 * 1024;
    private final SimulatorRequestAuthentication authentication;
    private final SimulatorVerifiedReception reception;

    public SimulatorReceiveController(SimulatorRequestAuthentication authentication,
            SimulatorVerifiedReception reception) {
        this.authentication = authentication;
        this.reception = reception;
    }

    @PostMapping(SimulatorInternalTargetRoute.PATH)
    public ResponseEntity<SimulatorVerifiedReception.Acknowledgement> receive(HttpServletRequest request) {
        String eventId = singleHeader(request, "X-HookFlow-Event-Id", 256);
        String timestamp = singleHeader(request, "X-HookFlow-Timestamp", 40);
        String signature = singleHeader(request, "X-HookFlow-Signature", 64);
        byte[] body;
        try {
            body = readBody(request.getInputStream());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        authentication.authenticate(eventId, timestamp, signature, body);
        return ResponseEntity.accepted().body(reception.acknowledge());
    }

    static byte[] readBody(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return body;
    }

    private static String singleHeader(HttpServletRequest request, String name, int maxLength) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            throw SimulatorRequestAuthentication.unauthorized();
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank() || value.length() > maxLength) {
            throw SimulatorRequestAuthentication.unauthorized();
        }
        return value;
    }
}
