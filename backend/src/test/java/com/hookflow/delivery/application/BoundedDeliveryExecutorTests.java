package com.hookflow.delivery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedDeliveryExecutorTests {
    @Test
    void startsFourWorkersAndDoesNotClaimCapacityBeyondRunningAndQueuedLimit() throws Exception {
        BoundedDeliveryExecutor executor = new BoundedDeliveryExecutor();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(20);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximumRunning = new AtomicInteger();
        List<BoundedDeliveryExecutor.Reservation> reservations = new ArrayList<>();
        try {
            assertEquals(4, executor.workerCount());
            assertEquals(16, executor.queueCapacity());
            for (int i = 0; i < 20; i++) {
                if (i == 4) {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                }
                var reservation = executor.reserve().orElseThrow();
                reservations.add(reservation);
                reservation.submit(() -> {
                    int active = running.incrementAndGet();
                    maximumRunning.accumulateAndGet(active, Math::max);
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("worker was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    } finally {
                        running.decrementAndGet();
                        completed.countDown();
                    }
                });
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(executor.reserve().isPresent(), "the full queue must not grant a claim permit");
            reservations.forEach(BoundedDeliveryExecutor.Reservation::close);
            assertFalse(executor.reserve().isPresent(), "closing submitted reservations must not free them early");
            assertEquals(4, maximumRunning.get());
        } finally {
            release.countDown();
            executor.close();
        }
        assertTrue(completed.await(30, TimeUnit.SECONDS));
        assertEquals(4, maximumRunning.get());
    }

    @Test
    void unsubmittedReservationsReleasePermitsExactlyOnce() throws Exception {
        BoundedDeliveryExecutor executor = new BoundedDeliveryExecutor();
        List<BoundedDeliveryExecutor.Reservation> reservations = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                reservations.add(executor.reserve().orElseThrow());
            }
            assertTrue(executor.reserve().isEmpty());
            reservations.getFirst().close();
            reservations.getFirst().close();
            BoundedDeliveryExecutor.Reservation reused = executor.reserve().orElseThrow();
            assertTrue(executor.reserve().isEmpty());
            reused.close();
            assertThrows(IllegalStateException.class, () -> reused.submit(() -> { }));
        } finally {
            reservations.forEach(BoundedDeliveryExecutor.Reservation::close);
            executor.close();
        }
    }

    @Test
    void failedSubmissionDoesNotConsumeCapacityAndCloseRejectsNewReservations() {
        BoundedDeliveryExecutor executor = new BoundedDeliveryExecutor();
        BoundedDeliveryExecutor.Reservation reservation = executor.reserve().orElseThrow();
        try {
            assertThrows(IllegalArgumentException.class, () -> reservation.submit(null));
            executor.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> reservation.submit(() -> { }));
            assertTrue(executor.reserve().isEmpty());
        } finally {
            reservation.close();
            executor.close();
        }
    }
}
