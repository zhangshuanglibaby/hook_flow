package com.hookflow.event.infrastructure; // 事件表的数据库操作放在基础设施层。

import com.hookflow.event.domain.CanonicalEventPayload; // 使用 P4-01 已生成的规范化内容和指纹。
import java.sql.PreparedStatement; // 通过预编译语句安全绑定插入参数。
import java.sql.Statement; // 请求数据库返回新事件的主键。
import java.time.LocalDateTime; // 对应数据库的 UTC DATETIME(3) 创建时间。
import java.util.Optional; // 查询不到事件时明确返回空值。
import org.springframework.context.annotation.Profile; // skeleton 模式不加载数据库组件。
import org.springframework.jdbc.core.JdbcTemplate; // 沿用项目现有的 JDBC 访问方式。
import org.springframework.jdbc.support.GeneratedKeyHolder; // 接收数据库生成的事件主键。
import org.springframework.stereotype.Repository; // 注册为仓库组件。

@Profile("!skeleton") // 仅在正式数据库模式下启用。
@Repository // 允许后续事件创建服务通过构造方法注入。
public class EventRepository { // 只封装 event 表的持久化操作。
    private final JdbcTemplate jdbcTemplate; // 保存项目提供的数据库访问工具。

    public EventRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate; // 供事件插入使用。
    }

    public long insert(String eventId, String eventType, long endpointId,
            CanonicalEventPayload payload, LocalDateTime createdAt) { // 由后续事务服务传入已校验字段和统一时间。
        GeneratedKeyHolder keys = new GeneratedKeyHolder(); // 准备接收数据库自增主键。
        int inserted = jdbcTemplate.update(connection -> { // 使用调用方开启的同一个数据库事务。
            PreparedStatement statement = connection.prepareStatement( // 创建带参数的插入语句。
                    "INSERT INTO event (event_id, event_type, payload_json, payload_fingerprint, "
                            + "endpoint_id, latest_round_no, current_status, created_at, terminal_at) "
                            + "VALUES (?, ?, ?, ?, ?, 1, 'PENDING', ?, NULL)", // 新事件固定为第 1 轮待投递、尚未终结。
                    Statement.RETURN_GENERATED_KEYS // 请求返回刚插入行的主键。
            );
            statement.setString(1, eventId); // 保存调用方事件 ID 的原值。
            statement.setString(2, eventType); // 保存调用方事件类型的原值。
            statement.setString(3, payload.json()); // 保存规范化 JSON，而非原始请求文本。
            statement.setBytes(4, payload.fingerprint()); // 保存对应的 32 字节 SHA-256 指纹。
            statement.setLong(5, endpointId); // 关联已校验的接收端。
            statement.setObject(6, createdAt); // 写入由事务服务提供的 UTC 创建时间。
            return statement; // 交给 JdbcTemplate 执行并读取生成的主键。
        }, keys);
        if (inserted != 1 || keys.getKey() == null) { // 插入数量或主键异常时不允许报告成功。
            throw new IllegalStateException("事件存储失败"); // 交由后续事务服务回滚整笔创建操作。
        }
        return keys.getKey().longValue(); // 返回内部主键，供同一事务创建首轮任务。
    }

    public Optional<ExistingEvent> findByEventId(String eventId) { // 按调用方事件 ID 读取已经提交的事件。
        return jdbcTemplate.query( // 使用当前调用方事务的数据库连接执行查询。
                "SELECT event_id, event_type, endpoint_id, payload_json, "
                        + "payload_fingerprint, latest_round_no, current_status, created_at "
                        + "FROM event WHERE event_id = ?", // 唯一索引保证最多返回一行。
                resultSet -> { // 将查询结果转换为可供去重比较的对象。
                    if (!resultSet.next()) { // 尚无这个 ID 的已提交事件。
                        return Optional.empty(); // 后续创建流程可以继续判断是否允许新建。
                    }
                    return Optional.of(new ExistingEvent( // 保存四元组和原事件的响应字段。
                            resultSet.getString("event_id"), // 原事件 ID。
                            resultSet.getString("event_type"), // 原事件类型。
                            resultSet.getLong("endpoint_id"), // 原接收端 ID。
                            resultSet.getString("payload_json"), // 原规范化 JSON，不能只依赖指纹。
                            resultSet.getBytes("payload_fingerprint"), // 原 SHA-256 指纹。
                            resultSet.getInt("latest_round_no"), // 查询时的最新轮号。
                            resultSet.getString("current_status"), // 查询时的最新轮状态。
                            resultSet.getObject("created_at", LocalDateTime.class) // 原创建时间，按项目约定表示 UTC。
                    ));
                },
                eventId // 绑定参数，不拼接外部输入。
        );
    }

    public Optional<Long> findEndpointId(long eventPk) { // 非锁定定位事件关联的接收端 ID。
        return jdbcTemplate.query( // 普通查询不取得事件行锁，也不改变事件状态。
                "SELECT endpoint_id FROM event WHERE id = ?", // 仅读取确定加锁顺序所需的字段。
                (rs, rowNum) -> rs.getLong("endpoint_id"), // 将数据库值转换为内部接收端 ID。
                eventPk // 绑定已定位的事件主键，不拼接 SQL。
        ).stream().findFirst(); // 事件不存在时返回空值，领取流程应跳过该候选。
    } // 定位结束；取得接收端锁后仍须重新锁定事件并复查关联。

    public Optional<LockedEvent> findByIdForUpdate(long eventPk) { // 在调用方已有的领取事务中，按内部主键锁定事件。
        return jdbcTemplate.query( // 使用当前事务的连接执行查询，行锁持续到该事务结束。
                "SELECT id, endpoint_id, latest_round_no, current_status " // 只读取领取时需要复查的事件字段。
                        + "FROM event WHERE id = ? FOR UPDATE", // 按主键当前读并锁行，不依赖先前候选查询的旧值。
                (rs, rowNum) -> new LockedEvent( // 将锁定行映射为不含事件内容的内部记录。
                        rs.getLong("id"), // 保留实际锁定的事件主键。
                        rs.getLong("endpoint_id"), // 供领取事务复查任务关联的接收端。
                        rs.getInt("latest_round_no"), // 供领取事务确认候选任务仍是最新轮。
                        rs.getString("current_status") // 供领取事务复查事件当前状态。
                ), // 结束单行映射；此处不更新事件。
                eventPk // 绑定已定位的事件内部主键，不拼接 SQL。
        ).stream().findFirst(); // 事件不存在时返回空值，由领取事务跳过候选任务。
    } // 锁定读取结束；锁的释放由调用方事务控制。

    public int markDelivering(LockedEvent event, String expectedStatus) { // 在调用方事务中同步最新轮事件状态。
        if (event == null || event.id() <= 0 || event.endpointId() <= 0
                || event.latestRoundNo() < 1) { // 拒绝缺失或不合法的持锁事件身份。
            throw new IllegalArgumentException("领取事件无效"); // 不对无法确认身份的事件写状态。
        }
        if (!"PENDING".equals(expectedStatus) && !"WAIT_RETRY".equals(expectedStatus)) { // 领取只允许两种待执行状态。
            throw new IllegalArgumentException("领取前事件状态无效"); // 禁止从暂停或终态直接转为投递中。
        }
        return jdbcTemplate.update( // 使用调用方已持有事件行锁的同一数据库事务。
                "UPDATE event SET current_status = 'DELIVERING', terminal_at = NULL "
                        + "WHERE id = ? AND endpoint_id = ? AND latest_round_no = ? "
                        + "AND current_status = ?", // 仅更新身份、接收端、最新轮和原状态均未变化的事件。
                event.id(), event.endpointId(), event.latestRoundNo(), expectedStatus // 逐一绑定条件参数。
        ); // 返回影响行数；后续领取服务必须要求恰好为 1，否则回滚整个领取事务。
    }

    public Optional<DeliveryPayload> findDeliveryPayload(long eventPk) { // 投递线程只读取已提交的事件快照。
        return jdbcTemplate.query("SELECT id, event_id, payload_json FROM event WHERE id = ?",
                (rs, row) -> new DeliveryPayload(rs.getLong("id"), rs.getString("event_id"),
                        rs.getString("payload_json")), eventPk).stream().findFirst();
    }

    public int markDeliveryCompleted(LockedEvent event, int roundNo, String status,
            LocalDateTime completedAt) { // 同一完成事务内更新最新轮事件的冗余状态。
        if (event.latestRoundNo() != roundNo || !"DELIVERING".equals(event.currentStatus())
                || !("SUCCESS".equals(status) || "WAIT_RETRY".equals(status)
                        || "PAUSED".equals(status) || "FINAL_FAILED".equals(status))) {
            throw new IllegalArgumentException("事件完成状态无效");
        }
        LocalDateTime terminalAt = "SUCCESS".equals(status) || "FINAL_FAILED".equals(status)
                ? completedAt : null;
        return jdbcTemplate.update("UPDATE event SET current_status = ?, terminal_at = ? "
                        + "WHERE id = ? AND endpoint_id = ? AND latest_round_no = ? "
                        + "AND current_status = 'DELIVERING'",
                status, terminalAt, event.id(), event.endpointId(), roundNo);
    }

    public int markPaused(LockedEvent event, int roundNo, String expectedStatus) {
        return jdbcTemplate.update("UPDATE event SET current_status = 'PAUSED', terminal_at = NULL "
                        + "WHERE id = ? AND endpoint_id = ? AND latest_round_no = ? AND current_status = ?",
                event.id(), event.endpointId(), roundNo, expectedStatus);
    }

    public record DeliveryPayload(long eventPk, String eventId, String payloadJson) {
    }

    public record LockedEvent(long id, long endpointId, int latestRoundNo,
            String currentStatus) { // 仅传递持锁后的核对数据，不包含 payload 或密钥。
    } // 事件锁定读取结果定义结束。

    public record ExistingEvent(String eventId, String eventType, long endpointId,
            String payloadJson, byte[] payloadFingerprint, int latestRoundNo,
            String currentStatus, LocalDateTime createdAt) { // 保存已提交事件的去重和响应所需字段。
        public ExistingEvent { // 构造时保护可变的指纹数组。
            payloadFingerprint = payloadFingerprint.clone(); // 不让 JDBC 返回的数组被外部修改。
        }

        @Override // 覆盖 record 自动生成的数组访问器。
        public byte[] payloadFingerprint() { // 向比较逻辑提供指纹。
            return payloadFingerprint.clone(); // 返回副本，避免修改本对象内部数据。
        }
    }
}