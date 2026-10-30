package com.hookflow.overview.application;

import com.hookflow.delivery.application.DeliveryAttemptCountQueryService;
import com.hookflow.event.application.EventStatisticsQueryService;
import com.hookflow.event.application.EventStatisticsQueryService.Counts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class OverviewQueryService {
    private final EventStatisticsQueryService events;
    private final DeliveryAttemptCountQueryService attempts;

    public OverviewQueryService(EventStatisticsQueryService events, DeliveryAttemptCountQueryService attempts) {
        this.events = events;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Overview query(LocalDateTime from, LocalDateTime to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        // 合法亚毫秒区间向上取整后可以有相同的两个毫秒边界。
        if (from.isAfter(to)) throw new IllegalArgumentException("统计范围无效");
        var counts = events.count(from, to);
        return summarize(counts, attempts.count(from, to));
    }

    static Overview summarize(Counts counts, long attemptCount) {
        var denominator = BigDecimal.valueOf(counts.success()).add(BigDecimal.valueOf(counts.finalFailed()));
        BigDecimal rate = denominator.signum() == 0 ? null
                : BigDecimal.valueOf(counts.success()).divide(denominator, 10, RoundingMode.HALF_UP);
        return new Overview(counts.total(), counts.statusCounts(), rate, attemptCount);
    }

    public record Overview(long eventCount, Map<String, Long> statusCounts, BigDecimal successRate, long attemptCount) { }
}
