package com.hookflow.simulator.infrastructure;

import com.hookflow.simulator.domain.SimulatorConfiguration;
import com.hookflow.simulator.domain.SimulatorConfiguration.Mode;
import com.hookflow.simulator.domain.StoredSimulatorConfiguration;
import com.hookflow.simulator.domain.SimulatorRequestRecord;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SimulatorRepository {
    private final JdbcTemplate jdbc;

    public SimulatorRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SimulatorConfiguration configuration(LocalDateTime now) {
        var configurations = readConfiguration();
        if (configurations.isEmpty()) {
            jdbc.update("INSERT INTO simulator_config (id, mode, fail_first_n, delay_ms, updated_at) "
                    + "VALUES (1, 'ALWAYS_200', 0, 0, ?) ON DUPLICATE KEY UPDATE id = id", now);
            configurations = readConfiguration();
        }
        return configurations.getFirst();
    }

    private java.util.List<SimulatorConfiguration> readConfiguration() {
        return jdbc.query("SELECT mode, fail_first_n, delay_ms FROM simulator_config WHERE id = 1",
                (rs, row) -> new SimulatorConfiguration(Mode.valueOf(rs.getString(1)), rs.getInt(2), rs.getInt(3)));
    }

    public StoredSimulatorConfiguration storedConfiguration(boolean lock) {
        return jdbc.queryForObject("SELECT mode, fail_first_n, delay_ms, updated_at FROM simulator_config WHERE id = 1"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new StoredSimulatorConfiguration(
                        new SimulatorConfiguration(Mode.valueOf(rs.getString(1)), rs.getInt(2), rs.getInt(3)),
                        rs.getObject(4, LocalDateTime.class)));
    }

    public void updateConfiguration(SimulatorConfiguration configuration, LocalDateTime now) {
        if (jdbc.update("UPDATE simulator_config SET mode=?, fail_first_n=?, delay_ms=?, updated_at=? WHERE id=1",
                configuration.mode().name(), configuration.failFirstN(), configuration.delayMs(), now) != 1) {
            throw new IllegalStateException("模拟器配置写入失败");
        }
    }

    public long requestCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM simulator_request_log", Long.class);
    }

    public List<SimulatorRequestRecord> requests(int size, long offset) {
        return jdbc.query("SELECT id, event_id, received_at, signature_valid, planned_status, duplicate_processed, config_mode "
                        + "FROM simulator_request_log ORDER BY received_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new SimulatorRequestRecord(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBoolean(4),
                        rs.getObject(5) == null ? null : rs.getInt(5), rs.getBoolean(6),
                        rs.getString(7) == null ? null : Mode.valueOf(rs.getString(7))), size, offset);
    }

    public State lockState(String eventId) {
        jdbc.update("INSERT INTO simulator_event_state (event_id, received_count) VALUES (?, 0) "
                + "ON DUPLICATE KEY UPDATE event_id = event_id", eventId);
        return jdbc.queryForObject("SELECT received_count, processed_at FROM simulator_event_state "
                        + "WHERE event_id = ? FOR UPDATE",
                (rs, row) -> new State(rs.getInt(1), rs.getObject(2, LocalDateTime.class)), eventId);
    }

    public void recordReception(String eventId, int count) {
        if (jdbc.update("UPDATE simulator_event_state SET received_count = ? WHERE event_id = ?", count, eventId) != 1) {
            throw new IllegalStateException("模拟器接收状态写入失败");
        }
    }

    public void processOnce(String eventId, LocalDateTime now) {
        if (jdbc.update("UPDATE simulator_event_state SET processed_at = ? "
                + "WHERE event_id = ? AND processed_at IS NULL", now, eventId) != 1) {
            throw new IllegalStateException("模拟器首次处理写入失败");
        }
    }

    public void log(String eventId, LocalDateTime now, boolean valid, int status, boolean duplicate, Mode mode) {
        jdbc.update("INSERT INTO simulator_request_log (event_id, received_at, signature_valid, "
                        + "planned_status, duplicate_processed, config_mode) VALUES (?, ?, ?, ?, ?, ?)",
                eventId, now, valid, status, duplicate, mode == null ? null : mode.name());
    }

    public record State(int receivedCount, LocalDateTime processedAt) { }
}
