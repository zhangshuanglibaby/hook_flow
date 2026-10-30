package com.hookflow.delivery.infrastructure;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class DeliveryAttemptCountRepository {
    private final JdbcTemplate jdbc;

    public DeliveryAttemptCountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long count(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE started_at >= ? AND started_at < ?",
                Long.class, from, to);
    }
}
