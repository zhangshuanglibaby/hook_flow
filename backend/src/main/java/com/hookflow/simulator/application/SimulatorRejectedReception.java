package com.hookflow.simulator.application;

import com.hookflow.simulator.infrastructure.SimulatorRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class SimulatorRejectedReception {
    private final SimulatorRepository repository;
    private final Clock clock;

    public SimulatorRejectedReception(SimulatorRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void record(int status) {
        if (status != 400 && status != 401) throw new IllegalArgumentException("模拟器拒绝状态不合法");
        // 未验签的事件身份不可信，且请求尚未进入模式处理。
        repository.log(null, LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC),
                false, status, false, null);
    }
}
