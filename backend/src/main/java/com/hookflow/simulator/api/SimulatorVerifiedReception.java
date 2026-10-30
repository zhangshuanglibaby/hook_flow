package com.hookflow.simulator.api;

import com.hookflow.simulator.application.SimulatorProcessingService;
import com.hookflow.simulator.application.SimulatorResponseDelay;
import com.hookflow.simulator.domain.SimulatorConfiguration.Decision;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!skeleton")
@Component
public class SimulatorVerifiedReception {
    private final SimulatorProcessingService processing;
    private final SimulatorResponseDelay delay;

    public SimulatorVerifiedReception(SimulatorProcessingService processing, SimulatorResponseDelay delay) {
        this.processing = processing;
        this.delay = delay;
    }

    public Decision acknowledge(String eventId) { return acknowledge(eventId, null); }

    public Decision acknowledge(String eventId, Long expectedPk) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("模拟器接收编排不能加入数据库事务");
        }
        var decision = processing.receiveVerified(eventId, expectedPk);
        delay.await(decision.delayMs());
        return decision;
    }

    public record Acknowledgement(boolean signatureVerified, boolean businessProcessed) { }
}
