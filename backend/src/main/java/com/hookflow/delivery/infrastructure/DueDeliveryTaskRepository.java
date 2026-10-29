package com.hookflow.delivery.infrastructure; // 到期任务的数据库查询属于投递基础设施层。

import java.math.BigDecimal; // 使用任务创建时保存的精确秒数计算退避。
import java.time.LocalDateTime; // 接收由应用层统一时钟生成的 UTC 数据库时间。
import java.util.List; // 返回按到期顺序排列的候选任务 ID。
import java.util.Objects; // 在查询前拒绝空时间。
import java.util.Optional; // 明确表示候选任务可能已被其他事务删除或改变。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 配置。
import org.springframework.jdbc.core.JdbcTemplate; // 使用项目现有的 JDBC 查询工具。
import org.springframework.stereotype.Repository; // 将仓库注册为 Spring 组件。
import org.springframework.transaction.annotation.Propagation; // 要求领取更新加入调用方已经开启的事务。
import org.springframework.transaction.annotation.Transactional; // 声明该方法不能自行开启和提交事务。

@Profile("!skeleton") // 无数据库的骨架测试不加载此仓库。
@Repository // 供后续 P6-01 的扫描应用服务调用。
public class DueDeliveryTaskRepository { // 只负责非锁定读取候选任务，不表示已经领取。
    private static final int BATCH_SIZE = 16; // 每次扫描只读取一小批候选任务。
    private final JdbcTemplate jdbcTemplate; // 保存项目现有的数据库访问工具。

    public DueDeliveryTaskRepository(JdbcTemplate jdbcTemplate) { // 通过构造方法注入 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate; // 后续查询使用同一个已配置的数据访问入口。
    }

    public List<Long> findDueCandidateIds(LocalDateTime now) { // 按调用方提供的 UTC 时间查找到期任务。
        Objects.requireNonNull(now, "扫描时间缺失"); // 不允许用数据库 NOW() 替代可控时钟。
        return jdbcTemplate.query( // 普通 SELECT 不加行锁，结果仅用于后续逐条尝试领取。
                "SELECT id FROM delivery_task "
                        + "WHERE status IN ('PENDING', 'WAIT_RETRY') "
                        + "AND next_run_at <= ? "
                        + "AND attempt_count < max_attempts "
                        + "ORDER BY next_run_at ASC, id ASC LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"), // 只取任务主键，不在此处读取密文快照。
                now, BATCH_SIZE // 参数化绑定到期时间和小批量上限。
        );
    }

    public List<DueCandidate> findDueCandidatesAfter(LocalDateTime now,
            LocalDateTime afterDueAt, long afterTaskId) { // 从上一条候选之后继续按到期时间和 ID 读取。
        Objects.requireNonNull(now, "扫描时间缺失"); // 扫描时间仍由应用层的可控 UTC 时钟提供。
        if (afterDueAt == null) { // 没有游标时，从最早的到期任务开始。
            return jdbcTemplate.query( // 普通 SELECT 不加锁，也不表示任务已被领取。
                    "SELECT id, next_run_at FROM delivery_task "
                            + "WHERE status IN ('PENDING', 'WAIT_RETRY') "
                            + "AND next_run_at <= ? AND attempt_count < max_attempts "
                            + "ORDER BY next_run_at ASC, id ASC LIMIT ?",
                    (rs, rowNum) -> new DueCandidate( // 同时返回排序键，供下一批继续读取。
                            rs.getLong("id"), // 保存候选任务的数据库主键。
                            rs.getObject("next_run_at", LocalDateTime.class) // 保存本次普通查询读到的到期时间。
                    ),
                    now, BATCH_SIZE // 每次数据库查询仍最多读取 16 条。
            );
        } // 无游标查询结束。
        if (afterTaskId <= 0) { // 有到期时间游标时，任务 ID 也必须有效。
            throw new IllegalArgumentException("候选任务游标无效"); // 拒绝不完整的排序游标。
        } // 完成游标检查。
        return jdbcTemplate.query( // 普通查询不加锁；领取时仍须持锁复查。
                "SELECT id, next_run_at FROM delivery_task " // 读取候选 ID 和排序所需的到期时间。
                        + "WHERE status IN ('PENDING', 'WAIT_RETRY') " // 只筛选两种可领取状态。
                        + "AND next_run_at <= ? AND attempt_count < max_attempts " // 排除未到期或次数耗尽的任务。
                        + "AND (next_run_at > ? OR (next_run_at = ? AND id > ?)) " // 从上一条排序键之后继续读取。
                        + "ORDER BY next_run_at ASC, id ASC LIMIT ?", // 保持稳定排序，且每批最多读取指定数量。
                (rs, rowNum) -> new DueCandidate( // 将数据库行映射为候选及其排序键。
                        rs.getLong("id"), // 保存候选任务 ID，供短事务尝试领取。
                        rs.getObject("next_run_at", LocalDateTime.class) // 保存到期时间，供下一批定位起点。
                ),
                now, afterDueAt, afterDueAt, afterTaskId, BATCH_SIZE // 按 SQL 占位符顺序绑定参数。
        );
    } // 本方法只读取候选，不修改任务或创建尝试。

    public Optional<DeliverySnapshot> findDeliverySnapshot(long taskId) { // 在领取提交后读取不可变投递快照。
        return jdbcTemplate.query("SELECT t.id, t.event_pk, t.round_no, t.status, t.attempt_count, "
                        + "t.max_attempts, t.retry_delay_1_s, t.retry_delay_2_s, t.timeout_ms_snapshot, "
                        + "t.target_type_snapshot, t.url_snapshot, t.secret_ciphertext_snapshot, "
                        + "t.claim_token, t.lease_until, "
                        + "e.event_id, e.payload_json FROM delivery_task t JOIN event e ON e.id = t.event_pk "
                        + "WHERE t.id = ?",
                (rs, row) -> new DeliverySnapshot(rs.getLong("id"), rs.getLong("event_pk"),
                        rs.getInt("round_no"), rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"), rs.getBigDecimal("retry_delay_1_s"),
                        rs.getBigDecimal("retry_delay_2_s"), rs.getInt("timeout_ms_snapshot"),
                        rs.getString("target_type_snapshot"), rs.getString("url_snapshot"),
                        rs.getBytes("secret_ciphertext_snapshot"), rs.getString("claim_token"),
                        rs.getObject("lease_until", LocalDateTime.class), rs.getString("event_id"),
                        rs.getString("payload_json")), taskId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedDeliveryTask> lockForCompletion(long taskId) { // 按完成写回顺序锁定任务当前行。
        return jdbcTemplate.query("SELECT id, event_pk, round_no, status, attempt_count, max_attempts, "
                        + "retry_delay_1_s, retry_delay_2_s, lease_until, claim_token "
                        + "FROM delivery_task WHERE id = ? FOR UPDATE",
                (rs, row) -> new LockedDeliveryTask(rs.getLong("id"), rs.getLong("event_pk"),
                        rs.getInt("round_no"), rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"), rs.getBigDecimal("retry_delay_1_s"),
                        rs.getBigDecimal("retry_delay_2_s"), rs.getObject("lease_until", LocalDateTime.class),
                        rs.getString("claim_token")), taskId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markCompleted(LockedDeliveryTask task, String token, String status,
            LocalDateTime nextRunAt, LocalDateTime completedAt) { // 条件写回任务状态和清理运行令牌。
        return jdbcTemplate.update("UPDATE delivery_task SET status = ?, next_run_at = ?, "
                        + "resume_status = ?, resume_due_at = ?, "
                        + "lease_until = NULL, claim_token = NULL, finished_at = ?, updated_at = ? "
                        + "WHERE id = ? AND event_pk = ? AND round_no = ? AND status = 'DELIVERING' "
                        + "AND claim_token = ? AND lease_until > ?",
                status, "WAIT_RETRY".equals(status) ? nextRunAt : null,
                "PAUSED".equals(status) ? "WAIT_RETRY" : null,
                "PAUSED".equals(status) ? nextRunAt : null,
                ("SUCCESS".equals(status) || "FINAL_FAILED".equals(status))
                        ? completedAt : null, completedAt, task.id(), task.eventPk(), task.roundNo(),
                token, completedAt);
    }

    public record DeliverySnapshot(long id, long eventPk, int roundNo, String status,
            int attemptCount, int maxAttempts, BigDecimal retryDelay1Seconds,
            BigDecimal retryDelay2Seconds, int timeoutMs, String targetType, String url,
            byte[] ciphertext, String claimToken, LocalDateTime leaseUntil,
            String eventId, String payloadJson) {
        public DeliverySnapshot {
            ciphertext = ciphertext.clone();
        }
        @Override public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    public record LockedDeliveryTask(long id, long eventPk, int roundNo, String status,
            int attemptCount, int maxAttempts, BigDecimal retryDelay1Seconds,
            BigDecimal retryDelay2Seconds, LocalDateTime leaseUntil, String claimToken) {
    }

    public record DueCandidate(long id, LocalDateTime dueAt) { // 保存非锁定查询得到的候选与排序键。
    } // 候选不是领取凭证；实际状态仍以持锁后的复查为准。


    public Optional<Long> findEventPk(long taskId) { // 非锁定定位候选任务所属事件，仅用于确定后续加锁路径。
        return jdbcTemplate.query( // 普通查询不领取任务，也不持有任务行锁。
                "SELECT event_pk FROM delivery_task WHERE id = ?", // 只读取任务关联的事件内部主键。
                (rs, rowNum) -> rs.getLong("event_pk"), // 将查询结果转换为事件主键。
                taskId // 绑定候选任务 ID，不拼接 SQL。
        ).stream().findFirst(); // 任务已不存在时返回空值，由领取流程跳过。
    } // 定位结束；领取事务锁定任务后仍必须重新核对 event_pk。

    public Optional<LockedDueTask> lockCandidate(long taskId) { // 仅供已按接收端、事件顺序取得行锁的领取事务调用。
        return jdbcTemplate.query( // 使用调用方事务的数据库连接执行当前读。
                "SELECT id, event_pk, round_no, status, attempt_count, max_attempts, " // 读取身份、轮次、状态和次数供持锁复查。
                        + "next_run_at, timeout_ms_snapshot " // 读取到期时间和计算租约所需的超时快照。
                        + "FROM delivery_task WHERE id = ? FOR UPDATE SKIP LOCKED", // 锁任务行；其他事务已锁住时直接跳过。
                (rs, rowNum) -> new LockedDueTask( // 将实际锁到的行映射为领取检查数据。
                        rs.getLong("id"), // 任务内部主键。
                        rs.getLong("event_pk"), // 复查任务仍属于先前锁定的事件。
                        rs.getInt("round_no"), // 复查任务仍是事件最新轮。
                        rs.getString("status"), // 复查当前状态，而非相信非锁定候选查询。
                        rs.getInt("attempt_count"), // 复查当前已消耗的尝试次数。
                        rs.getInt("max_attempts"), // 与已消耗次数比较，防止超额领取。
                        rs.getObject("next_run_at", LocalDateTime.class), // 复查到期时间；其他状态允许为 null。
                        rs.getInt("timeout_ms_snapshot") // 后续按本轮快照计算足够长的租约。
                ),
                taskId // 通过参数绑定候选任务 ID。
        ).stream().findFirst(); // 行不存在或被跳过时返回空值，不把候选查询视作领取成功。
    }

    public record LockedDueTask(long id, long eventPk, int roundNo, String status, // 保存持锁后读取的任务身份和状态。
            int attemptCount, int maxAttempts, LocalDateTime nextRunAt, // 保存次数及当前到期时间。
            int timeoutMsSnapshot) { // 保存创建任务时固定的请求超时快照。
    }

    @Transactional(propagation = Propagation.MANDATORY) // 必须与后续尝试插入及事件状态更新共用一个领取事务。
    public int markDelivering(LockedDueTask task, LocalDateTime now, // 接收刚才锁定的任务及本事务统一的 UTC 时间。
            String claimToken, LocalDateTime leaseUntil) { // 接收本次唯一令牌及由调用方计算的租约截止时间。
        Objects.requireNonNull(task, "任务缺失"); // 不允许在缺少持锁任务数据时更新。
        Objects.requireNonNull(now, "领取时间缺失"); // 不使用数据库 NOW()，保持可控时钟一致。
        Objects.requireNonNull(leaseUntil, "租约截止时间缺失"); // 租约必须由领取流程显式给出。
        if (claimToken == null || claimToken.isBlank() || claimToken.length() > 64) { // 符合数据库令牌列的长度与非空要求。
            throw new IllegalArgumentException("领取令牌无效"); // 拒绝无法标识本次领取的令牌。
        }
        if (!leaseUntil.isAfter(now.plusNanos( // 租约必须长于任务快照超时并留出缓冲。
                (long) task.timeoutMsSnapshot() * 1_000_000L).plusSeconds(30))) { // 额外预留超过 30 秒。
            throw new IllegalArgumentException("领取租约不足"); // 不写入可能在 HTTP 超时前到期的租约。
        }
        return jdbcTemplate.update( // 在调用方事务内执行条件更新；失败时由同一事务回滚后续写入。
                "UPDATE delivery_task SET status = 'DELIVERING', " // 当前轮进入投递中状态。
                        + "attempt_count = attempt_count + 1, next_run_at = NULL, " // 消耗一次次数并清除待运行时间。
                        + "claim_token = ?, lease_until = ?, updated_at = ? " // 保存本次令牌、租约和统一更新时间。
                        + "WHERE id = ? AND event_pk = ? AND round_no = ? " // 再次限定任务及其不可变关联。
                        + "AND status = ? AND next_run_at <= ? " // 只更新仍处于原待执行状态且已到期的任务。
                        + "AND attempt_count = ? AND attempt_count < max_attempts", // 防止旧读取或次数耗尽时领取。
                claimToken, leaseUntil, now, // 与 SET 子句中的三个占位符依次对应。
                task.id(), task.eventPk(), task.roundNo(), // 与任务身份及轮次条件依次对应。
                task.status(), now, task.attemptCount() // 与当前状态、到期时间和原次数条件依次对应。
        ); // 返回受影响行数；调用方必须只将 1 视为本次更新成功。
    }
}