package com.hookflow.audit.infrastructure;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class AuditRetentionRepository {
    private final JdbcTemplate jdbc;

    public AuditRetentionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int deleteExpired(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM audit_log WHERE occurred_at <= ? ORDER BY occurred_at,id LIMIT 100", cutoff);
    }
}
