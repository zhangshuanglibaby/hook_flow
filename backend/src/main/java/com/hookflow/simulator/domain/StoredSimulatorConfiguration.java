package com.hookflow.simulator.domain;

import java.time.LocalDateTime;

public record StoredSimulatorConfiguration(SimulatorConfiguration configuration, LocalDateTime updatedAt) { }
