package com.hookflow.simulator.domain;

import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import java.time.Instant;

public record SimulatorRequestRecord(long id, String eventId, Instant receivedAt, boolean signatureValid,
        Integer plannedStatus, boolean duplicateProcessed, Mode configMode) { }
