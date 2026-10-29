package com.hookflow.delivery.infrastructure;

import com.hookflow.delivery.application.DeliveryTaskPolicy;
import com.hookflow.endpoint.domain.EndpointTaskSnapshot;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Repository
public class DeliveryRedeliveryRepository {
    private final JdbcTemplate jdbc;

    public DeliveryRedeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> lockLatestStatus(long eventPk, int roundNo) {
        return jdbc.query("SELECT status FROM delivery_task WHERE event_pk = ? AND round_no = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), eventPk, roundNo).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long insert(long eventPk, int roundNo, EndpointTaskSnapshot snapshot,
            DeliveryTaskPolicy policy, LocalDateTime now) {
        var keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO delivery_task (event_pk, round_no, "
                    + "status, attempt_count, max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                    + "resume_status, resume_due_at, lease_until, claim_token, target_type_snapshot, url_snapshot, "
                    + "timeout_ms_snapshot, secret_ciphertext_snapshot, created_at, updated_at, finished_at) "
                    + "VALUES (?, ?, 'PENDING', 0, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, eventPk);
            statement.setInt(2, roundNo);
            statement.setInt(3, policy.maxAttempts());
            statement.setBigDecimal(4, policy.retryDelay1Seconds());
            statement.setBigDecimal(5, policy.retryDelay2Seconds());
            statement.setObject(6, now);
            statement.setString(7, snapshot.targetType().name());
            statement.setString(8, snapshot.url());
            statement.setInt(9, snapshot.timeoutMs());
            statement.setBytes(10, snapshot.secretCiphertextSnapshot());
            statement.setObject(11, now);
            statement.setObject(12, now);
            return statement;
        }, keys);
        if (inserted != 1 || keys.getKey() == null) {
            throw new IllegalStateException("重投任务存储失败");
        }
        return keys.getKey().longValue();
    }
}
