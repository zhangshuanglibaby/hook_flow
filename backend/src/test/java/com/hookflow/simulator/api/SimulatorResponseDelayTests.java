package com.hookflow.simulator.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hookflow.simulator.application.SimulatorProcessingService;
import com.hookflow.simulator.application.SimulatorResponseDelay;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SimulatorResponseDelayTests {
    @Test void delayAndReceptionRejectCallerTransactionsBeforeAnyProcessing() {
        var processing = mock(SimulatorProcessingService.class);
        var delay = new SimulatorResponseDelay();
        var reception = new SimulatorVerifiedReception(processing, delay);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> delay.await(1));
            assertThrows(IllegalStateException.class, () -> reception.acknowledge("event"));
            verifyNoInteractions(processing);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertDoesNotThrow(() -> delay.await(0));
        assertThrows(IllegalArgumentException.class, () -> delay.await(-1));
        assertThrows(IllegalArgumentException.class, () -> delay.await(30001));
    }

    @Test void interruptedDelayDoesNotPretendResponseWasDeliveredAndKeepsInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> new SimulatorResponseDelay().await(1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
