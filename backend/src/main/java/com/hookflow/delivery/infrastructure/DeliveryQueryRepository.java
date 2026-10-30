package com.hookflow.delivery.infrastructure;

import com.hookflow.common.api.PageParameters;
import com.hookflow.delivery.application.DeliveryRedeliveryService.TaskSummary;
import com.hookflow.event.application.EventDeliveryQueryAccess.Selection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class DeliveryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public DeliveryQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public LatestTasks selection(Selection events, String status) {
        var parameters = new MapSqlParameterSource(events.parameters());
        String sql = " FROM (" + events.sql() + ") e JOIN delivery_task t "
                + "ON t.event_pk = e.event_pk AND t.round_no = e.latest_round_no";
        if (status != null) { sql += " WHERE t.status = :status"; parameters.addValue("status", status); }
        return new LatestTasks(sql, parameters.getValues());
    }

    public long count(LatestTasks selection) {
        return jdbc.queryForObject("SELECT COUNT(*)" + selection.sql(), selection.parameters(), Long.class);
    }

    public List<TaskSummary> page(LatestTasks selection, PageParameters page) {
        var parameters = new MapSqlParameterSource(selection.parameters()).addValue("limit", page.size()).addValue("offset", page.offset());
        return jdbc.query("SELECT t.id, e.event_id, e.event_type, e.endpoint_id, t.round_no, t.status, "
                        + "t.attempt_count, t.max_attempts, t.created_at, "
                        + "(SELECT a.started_at FROM delivery_attempt a WHERE a.task_id = t.id "
                        + "ORDER BY a.attempt_no DESC LIMIT 1) AS last_attempt_at"
                        + selection.sql() + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit OFFSET :offset",
                parameters, (rs, row) -> {
                    var last = rs.getObject("last_attempt_at", LocalDateTime.class);
                    return new TaskSummary(rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"),
                            rs.getLong("endpoint_id"), rs.getInt("round_no"), rs.getString("status"),
                            rs.getInt("attempt_count"), rs.getInt("max_attempts"), last == null ? null : last.toInstant(ZoneOffset.UTC),
                            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                });
    }

    public record LatestTasks(String sql, Map<String, Object> parameters) {
        public LatestTasks { parameters = Map.copyOf(parameters); }
    }
}
