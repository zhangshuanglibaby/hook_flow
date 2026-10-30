package com.hookflow.simulator.domain;

public record SimulatorConfiguration(Mode mode, int failFirstN, int delayMs) {
    public enum Mode { ALWAYS_200, ALWAYS_500, FAIL_FIRST_N, PROCESSED_THEN_DELAY }

    public SimulatorConfiguration {
        if (mode == null || failFirstN < 0 || failFirstN > 10 || delayMs < 0 || delayMs > 30000
                || mode != Mode.FAIL_FIRST_N && failFirstN != 0
                || (mode == Mode.PROCESSED_THEN_DELAY ? delayMs == 0 : delayMs != 0)) {
            throw new IllegalArgumentException("模拟器配置不合法");
        }
    }

    public Decision decide(int receivedCount, boolean processed) {
        if (receivedCount < 1) throw new IllegalArgumentException("模拟器接收次数不合法");
        if (processed) return new Decision(200, true, 0);
        return switch (mode) {
            case ALWAYS_200 -> new Decision(200, false, 0);
            case ALWAYS_500 -> new Decision(500, false, 0);
            case FAIL_FIRST_N -> new Decision(receivedCount <= failFirstN ? 500 : 200, false, 0);
            case PROCESSED_THEN_DELAY -> new Decision(200, false, delayMs);
        };
    }

    public record Decision(int plannedStatus, boolean duplicateProcessed, int delayMs) { }
}
