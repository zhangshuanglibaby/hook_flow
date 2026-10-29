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
public class DeliveryResumeRepository {
    private final JdbcTemplate jdbc;

    public DeliveryResumeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ResumeCandidate> findCandidates(long endpointId) {
        // 接收端锁保护下定位；最终状态必须在事件、任务行锁之后复查。
        return jdbc.query("SELECT t.id, t.event_pk FROM delivery_task t "
                        + "JOIN event e ON e.id = t.event_pk WHERE e.endpoint_id = ? "
                        + "AND t.round_no = e.latest_round_no AND t.status = 'PAUSED' "
                        + "ORDER BY t.event_pk, t.id",
                (rs, row) -> new ResumeCandidate(rs.getLong("id"), rs.getLong("event_pk")), endpointId);
    }

    public Optional<ResumeTask> lock(long taskId) {
        return jdbc.query("SELECT id, event_pk, round_no, status, next_run_at, resume_status, "
                        + "resume_due_at, claim_token, lease_until FROM delivery_task WHERE id = ? FOR UPDATE",
                (rs, row) -> new ResumeTask(rs.getLong("id"), rs.getLong("event_pk"),
                        rs.getInt("round_no"), rs.getString("status"),
                        rs.getObject("next_run_at", LocalDateTime.class), rs.getString("resume_status"),
                        rs.getObject("resume_due_at", LocalDateTime.class), rs.getString("claim_token"),
                        rs.getObject("lease_until", LocalDateTime.class)), taskId).stream().findFirst();
    }

    public int resume(ResumeTask task, LocalDateTime dueAt, LocalDateTime now) {
        return jdbc.update("UPDATE delivery_task SET status = ?, next_run_at = ?, "
                        + "resume_status = NULL, resume_due_at = NULL, updated_at = ? "
                        + "WHERE id = ? AND event_pk = ? AND round_no = ? AND status = 'PAUSED' "
                        + "AND resume_status = ? AND resume_due_at = ? "
                        + "AND next_run_at IS NULL AND claim_token IS NULL AND lease_until IS NULL",
                task.resumeStatus(), dueAt, now, task.id(), task.eventPk(), task.roundNo(),
                task.resumeStatus(), task.resumeDueAt());
    }

    public record ResumeCandidate(long taskId, long eventPk) { }
    public record ResumeTask(long id, long eventPk, int roundNo, String status,
            LocalDateTime nextRunAt, String resumeStatus, LocalDateTime resumeDueAt,
            String claimToken, LocalDateTime leaseUntil) { }
}
