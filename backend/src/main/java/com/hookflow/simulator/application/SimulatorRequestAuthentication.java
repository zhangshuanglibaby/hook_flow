package com.hookflow.simulator.application;

import com.hookflow.delivery.application.SimulatorDeliverySignatureAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Profile("!skeleton")
@Service
public class SimulatorRequestAuthentication {
    public static final Duration TIME_WINDOW = Duration.ofMinutes(5);
    private final Clock clock;
    private final SimulatorDeliverySignatureAccess signatures;

    public SimulatorRequestAuthentication(Clock clock, SimulatorDeliverySignatureAccess signatures) {
        this.clock = clock;
        this.signatures = signatures;
    }

    public long authenticate(String eventId, String timestamp, String signature, byte[] rawBody) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 256
                || eventId.codePointCount(0, eventId.length()) > 128
                || eventId.codePoints().anyMatch(c -> Character.isISOControl(c)
                        || c >= 0xD800 && c <= 0xDFFF)
                || timestamp == null || timestamp.length() > 40
                || signature == null || !signature.matches("[0-9a-f]{64}")) {
            throw unauthorized();
        }
        Instant sentAt;
        try {
            sentAt = Instant.parse(timestamp);
            if (!sentAt.toString().equals(timestamp)) {
                throw unauthorized();
            }
        } catch (DateTimeParseException exception) {
            throw unauthorized();
        }
        Duration skew = Duration.between(sentAt, clock.instant());
        if (skew.compareTo(TIME_WINDOW) > 0 || skew.compareTo(TIME_WINDOW.negated()) < 0) throw unauthorized();
        Long eventPk = signatures.verifiedEventPk(eventId, timestamp, rawBody, HexFormat.of().parseHex(signature));
        if (eventPk == null) throw unauthorized();
        return eventPk;
    }

    public static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
