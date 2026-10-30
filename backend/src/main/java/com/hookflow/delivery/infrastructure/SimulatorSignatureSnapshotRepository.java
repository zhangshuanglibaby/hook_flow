package com.hookflow.delivery.infrastructure;

import java.util.function.Predicate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!skeleton")
@Repository
public class SimulatorSignatureSnapshotRepository {
    private final JdbcTemplate jdbc;

    public SimulatorSignatureSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean matches(long eventPk, int latestRoundNo, Predicate<byte[]> verify) {
        // 协议不携带轮次；逐页检查已提交快照，不以当前配置替代历史密钥。
        boolean matched = false;
        boolean latestFound = false;
        int after = 0;
        while (true) {
            var page = jdbc.query("SELECT round_no, target_type_snapshot, url_snapshot, secret_ciphertext_snapshot "
                            + "FROM delivery_task WHERE event_pk = ? AND round_no > ? AND round_no <= ? "
                            + "ORDER BY round_no LIMIT 16",
                    (rs, row) -> new Snapshot(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBytes(4)),
                    eventPk, after, latestRoundNo);
            for (var snapshot : page) {
                if (!"SIMULATOR".equals(snapshot.targetType()) || snapshot.url() != null) {
                    return false;
                }
                matched |= verify.test(snapshot.ciphertext());
                latestFound |= snapshot.roundNo() == latestRoundNo;
                after = snapshot.roundNo();
            }
            if (page.size() < 16) {
                return matched && latestFound;
            }
        }
    }

    private record Snapshot(int roundNo, String targetType, String url, byte[] ciphertext) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
}
