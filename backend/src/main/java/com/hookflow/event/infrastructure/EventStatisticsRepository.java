package com.hookflow.event.infrastructure;

import com.hookflow.event.application.EventStatisticsQueryService.Counts;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class EventStatisticsRepository {
    private final JdbcTemplate jdbc;

    public EventStatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Counts count(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(current_status = 'PENDING'), 0) AS pending,
                       COALESCE(SUM(current_status = 'DELIVERING'), 0) AS delivering,
                       COALESCE(SUM(current_status = 'WAIT_RETRY'), 0) AS wait_retry,
                       COALESCE(SUM(current_status = 'PAUSED'), 0) AS paused,
                       COALESCE(SUM(current_status = 'SUCCESS'), 0) AS success,
                       COALESCE(SUM(current_status = 'FINAL_FAILED'), 0) AS final_failed
                FROM event WHERE created_at >= ? AND created_at < ?
                """, (rs, row) -> new Counts(rs.getLong("pending"), rs.getLong("delivering"),
                rs.getLong("wait_retry"), rs.getLong("paused"), rs.getLong("success"),
                rs.getLong("final_failed")), from, to);
    }
}
