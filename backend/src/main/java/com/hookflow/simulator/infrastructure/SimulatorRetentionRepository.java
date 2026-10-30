package com.hookflow.simulator.infrastructure;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class SimulatorRetentionRepository {
    private final JdbcTemplate jdbc;

    public SimulatorRetentionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void deleteState(String eventId) { jdbc.update("DELETE FROM simulator_event_state WHERE event_id=?", eventId); }

    public int deleteRequests(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM simulator_request_log WHERE received_at <= ? ORDER BY received_at,id LIMIT 100", cutoff);
    }
}
