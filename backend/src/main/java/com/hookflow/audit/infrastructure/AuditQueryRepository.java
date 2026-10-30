package com.hookflow.audit.infrastructure;

import com.hookflow.audit.application.AuditQueryService.Entry;
import com.hookflow.common.api.PageParameters;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class AuditQueryRepository {
    private final JdbcTemplate jdbc;

    public AuditQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class); }

    public List<Entry> page(PageParameters page) {
        return jdbc.query("SELECT id, actor_user_id, action, object_type, object_id, occurred_at, request_id "
                        + "FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Entry(rs.getLong("id"), rs.getLong("actor_user_id"), rs.getString("action"),
                        rs.getString("object_type"), rs.getString("object_id"),
                        rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("request_id")),
                page.size(), page.offset());
    }
}
