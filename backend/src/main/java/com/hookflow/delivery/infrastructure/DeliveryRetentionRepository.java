package com.hookflow.delivery.infrastructure;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class DeliveryRetentionRepository {
    private final JdbcTemplate jdbc;

    public DeliveryRetentionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean eligible(long eventPk, int latestRound, String status, LocalDateTime terminalAt) {
        var tasks = jdbc.queryForList("SELECT round_no,status,finished_at FROM delivery_task WHERE event_pk=? ORDER BY id FOR UPDATE", eventPk);
        if (tasks.size() != latestRound) return false;
        boolean latestMatches = false;
        for (var task : tasks) {
            if (!("SUCCESS".equals(task.get("status")) || "FINAL_FAILED".equals(task.get("status"))) || task.get("finished_at") == null) return false;
            if (((Number) task.get("round_no")).intValue() == latestRound)
                latestMatches = status.equals(task.get("status")) && terminalAt.equals(task.get("finished_at"));
        }
        return latestMatches && jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt a JOIN delivery_task t ON t.id=a.task_id "
                + "WHERE t.event_pk=? AND a.result='STARTED'", Long.class, eventPk) == 0;
    }

    public void deleteHistory(long eventPk) {
        jdbc.update("DELETE a FROM delivery_attempt a JOIN delivery_task t ON t.id=a.task_id WHERE t.event_pk=?", eventPk);
        jdbc.update("DELETE FROM delivery_task WHERE event_pk=?", eventPk);
    }
}
