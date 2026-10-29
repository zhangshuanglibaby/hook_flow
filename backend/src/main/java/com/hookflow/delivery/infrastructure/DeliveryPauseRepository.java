package com.hookflow.delivery.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class DeliveryPauseRepository {
    private final JdbcTemplate jdbc;

    public DeliveryPauseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PauseCandidate> findCandidates(long endpointId) {
        // 接收端锁保护下的 READ_COMMITTED 定位查询；不能在事件锁之前锁任务。
        return jdbc.query("SELECT t.id, t.event_pk FROM delivery_task t "
                        + "JOIN event e ON e.id = t.event_pk WHERE e.endpoint_id = ? "
                        + "AND t.round_no = e.latest_round_no AND t.status IN ('PENDING', 'WAIT_RETRY') "
                        + "ORDER BY t.event_pk, t.id",
                (rs, row) -> new PauseCandidate(rs.getLong("id"), rs.getLong("event_pk")), endpointId);
    }

    public Optional<PauseTask> lock(long taskId) {
        return jdbc.query("SELECT id, event_pk, round_no, status, next_run_at FROM delivery_task "
                        + "WHERE id = ? FOR UPDATE",
                (rs, row) -> new PauseTask(rs.getLong("id"), rs.getLong("event_pk"),
                        rs.getInt("round_no"), rs.getString("status"),
                        rs.getObject("next_run_at", LocalDateTime.class)), taskId).stream().findFirst();
    }

    public int pause(PauseTask task, LocalDateTime now) {
        return jdbc.update("UPDATE delivery_task SET status = 'PAUSED', resume_status = ?, "
                        + "resume_due_at = ?, next_run_at = NULL, updated_at = ? "
                        + "WHERE id = ? AND event_pk = ? AND round_no = ? AND status = ? "
                        + "AND next_run_at = ? AND status IN ('PENDING', 'WAIT_RETRY')",
                task.status(), task.dueAt(), now, task.id(), task.eventPk(), task.roundNo(),
                task.status(), task.dueAt());
    }

    public record PauseCandidate(long taskId, long eventPk) { }
    public record PauseTask(long id, long eventPk, int roundNo, String status, LocalDateTime dueAt) { }
}
