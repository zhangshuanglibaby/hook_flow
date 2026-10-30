package com.hookflow.event.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class EventRetentionRepository {
    private final JdbcTemplate jdbc;

    public EventRetentionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Long> candidates(LocalDateTime cutoff, long afterId) {
        return jdbc.queryForList("SELECT id FROM event WHERE id > ? AND current_status IN ('SUCCESS','FINAL_FAILED') "
                + "AND terminal_at <= ? ORDER BY id LIMIT 16", Long.class, afterId, cutoff);
    }

    public Optional<Long> endpoint(long id) {
        return jdbc.query("SELECT endpoint_id FROM event WHERE id=?", (rs, row) -> rs.getLong(1), id).stream().findFirst();
    }

    public Optional<RetainedEvent> lock(long id) {
        return jdbc.query("SELECT id,event_id,endpoint_id,latest_round_no,current_status,terminal_at FROM event WHERE id=? FOR UPDATE",
                (rs, row) -> new RetainedEvent(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4),
                        rs.getString(5), rs.getObject(6, LocalDateTime.class)), id).stream().findFirst();
    }

    public boolean lockLiveForSimulator(String eventId, Long expectedPk) {
        var located = jdbc.query("SELECT id FROM event WHERE event_id=?", (rs, row) -> rs.getLong(1), eventId);
        if (located.isEmpty()) return false;
        long id = expectedPk == null ? located.getFirst() : expectedPk;
        return !jdbc.query("SELECT id FROM event WHERE id=? AND event_id=? FOR SHARE",
                (rs, row) -> rs.getLong(1), id, eventId).isEmpty();
    }

    public void delete(RetainedEvent event) {
        if (jdbc.update("DELETE FROM event WHERE id=? AND latest_round_no=? AND current_status=? AND terminal_at=?",
                event.id(), event.roundNo(), event.status(), event.terminalAt()) != 1) throw new IllegalStateException("事件保留清理失败");
    }

    public record RetainedEvent(long id, String eventId, long endpointId, int roundNo, String status, LocalDateTime terminalAt) { }
}
