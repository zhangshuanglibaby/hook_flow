package com.hookflow.event.infrastructure;

import com.hookflow.common.api.PageParameters;
import com.hookflow.event.application.EventQueryService.Filter;
import com.hookflow.event.application.EventQueryService.Summary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class EventQueryRepository {
    private static final String SUMMARY = "event_id, event_type, endpoint_id, current_status, latest_round_no, created_at";
    private final NamedParameterJdbcTemplate jdbc;

    public EventQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long count(Filter filter) {
        var criteria = criteria(filter);
        return jdbc.queryForObject("SELECT COUNT(*) FROM event" + criteria.sql(), criteria.parameters(), Long.class);
    }

    public List<Summary> page(Filter filter, PageParameters page) {
        var criteria = criteria(filter);
        criteria.parameters().addValue("limit", page.size()).addValue("offset", page.offset());
        return jdbc.query("SELECT " + SUMMARY + " FROM event" + criteria.sql()
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                criteria.parameters(), (rs, row) -> summary(rs));
    }

    public Optional<StoredDetail> find(String eventId) {
        return jdbc.query("SELECT id, " + SUMMARY + ", payload_json FROM event WHERE event_id = :eventId",
                new MapSqlParameterSource("eventId", eventId),
                (rs, row) -> new StoredDetail(rs.getLong("id"), summary(rs), rs.getString("payload_json")))
                .stream().findFirst();
    }

    private static Criteria criteria(Filter filter) {
        var sql = new StringBuilder(" WHERE 1 = 1");
        var parameters = new MapSqlParameterSource();
        if (filter.eventId() != null) { sql.append(" AND event_id = :eventId"); parameters.addValue("eventId", filter.eventId()); }
        if (filter.eventType() != null) { sql.append(" AND event_type = :eventType"); parameters.addValue("eventType", filter.eventType()); }
        if (filter.status() != null) { sql.append(" AND current_status = :status"); parameters.addValue("status", filter.status()); }
        if (filter.endpointId() != null) { sql.append(" AND endpoint_id = :endpointId"); parameters.addValue("endpointId", filter.endpointId()); }
        if (filter.from() != null) { sql.append(" AND created_at >= :from"); parameters.addValue("from", filter.from()); }
        if (filter.to() != null) { sql.append(" AND created_at < :to"); parameters.addValue("to", filter.to()); }
        return new Criteria(sql.toString(), parameters);
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getString("event_id"), rs.getString("event_type"), rs.getLong("endpoint_id"),
                rs.getString("current_status"), rs.getInt("latest_round_no"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private record Criteria(String sql, MapSqlParameterSource parameters) { }
    public record StoredDetail(long eventPk, Summary summary, String payloadJson) {
        @Override public String toString() { return "StoredEventDetail[redacted]"; }
    }
}
