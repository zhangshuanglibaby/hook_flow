package com.hookflow.delivery.infrastructure;

import com.hookflow.delivery.application.DeliveryHistoryQueryService.Attempt;
import com.hookflow.delivery.application.DeliveryHistoryQueryService.Round;
import com.hookflow.delivery.application.DeliveryHistoryQueryService.StoredTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class DeliveryHistoryRepository {
    private final JdbcTemplate jdbc;

    public DeliveryHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Round> rounds(long eventPk) {
        var rounds = new LinkedHashMap<Long, Round>();
        jdbc.query("SELECT id, round_no, status, attempt_count, max_attempts, next_run_at, created_at, finished_at "
                        + "FROM delivery_task WHERE event_pk = ? ORDER BY round_no ASC",
                (rs, row) -> new Round(rs.getLong("id"), rs.getInt("round_no"), rs.getString("status"),
                        rs.getInt("attempt_count"), rs.getInt("max_attempts"), instant(rs, "next_run_at"),
                        instant(rs, "created_at"), instant(rs, "finished_at"), new ArrayList<>()), eventPk)
                .forEach(round -> rounds.put(round.taskId(), round));
        jdbc.query("SELECT a.task_id, a.attempt_no, a.result, a.started_at, a.ended_at, a.duration_ms, "
                        + "a.http_status, a.response_excerpt, a.error_code, a.error_message "
                        + "FROM delivery_attempt a JOIN delivery_task t ON t.id = a.task_id "
                        + "WHERE t.event_pk = ? ORDER BY t.round_no ASC, a.attempt_no ASC",
                (rs, row) -> {
                    var round = rounds.get(rs.getLong("task_id"));
                    if (round == null) throw new IllegalStateException("投递尝试所属轮次缺失");
                    round.attempts().add(attempt(rs));
                    return Boolean.TRUE;
                }, eventPk);
        return rounds.values().stream().map(round -> new Round(round.taskId(), round.roundNo(), round.status(),
                round.attemptCount(), round.maxAttempts(), round.nextRunAt(), round.createdAt(), round.finishedAt(),
                List.copyOf(round.attempts()))).toList();
    }

    public Optional<StoredTask> task(long taskId) {
        return jdbc.query("SELECT id, event_pk, round_no, status, attempt_count, max_attempts, next_run_at, created_at, finished_at "
                        + "FROM delivery_task WHERE id = ?",
                (rs, row) -> new StoredTask(rs.getLong("event_pk"),
                        new Round(rs.getLong("id"), rs.getInt("round_no"), rs.getString("status"), rs.getInt("attempt_count"),
                                rs.getInt("max_attempts"), instant(rs, "next_run_at"), instant(rs, "created_at"),
                                instant(rs, "finished_at"), List.of())), taskId).stream().findFirst();
    }

    public List<Attempt> attempts(long taskId) {
        return jdbc.query("SELECT attempt_no, result, started_at, ended_at, duration_ms, http_status, "
                        + "response_excerpt, error_code, error_message FROM delivery_attempt WHERE task_id = ? ORDER BY attempt_no ASC",
                (rs, row) -> attempt(rs), taskId);
    }

    private static Attempt attempt(ResultSet rs) throws SQLException {
        return new Attempt(rs.getInt("attempt_no"), rs.getString("result"), instant(rs, "started_at"),
                instant(rs, "ended_at"), rs.getObject("duration_ms", Long.class), rs.getObject("http_status", Integer.class),
                rs.getString("response_excerpt"), rs.getString("error_code"), rs.getString("error_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
