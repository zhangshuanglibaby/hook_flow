package com.hookflow.simulator.api;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!skeleton")
@Component
public class SimulatorVerifiedReception {
    // P8-01 只确认签名认证；模式处理和持久化幂等尚未接入。
    public Acknowledgement acknowledge() {
        return new Acknowledgement(true, false);
    }

    public record Acknowledgement(boolean signatureVerified, boolean businessProcessed) { }
}
