package com.hookflow.simulator.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!skeleton")
@Component
public class SimulatorResponseDelay {
    public void await(int delayMs) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("模拟器不能在数据库事务内延迟响应");
        }
        if (delayMs < 0 || delayMs > 30000) throw new IllegalArgumentException("模拟器延迟不合法");
        if (delayMs == 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟器响应等待中断");
        }
    }
}
