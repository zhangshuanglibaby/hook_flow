package com.hookflow.delivery.infrastructure; // 首轮任务的数据库写入属于投递基础设施层。

import com.hookflow.endpoint.domain.EndpointTaskSnapshot; // 使用持锁取得的接收端配置快照。
import java.math.BigDecimal; // 精确保留数据库 DECIMAL(30,9) 的重试秒数。
import java.sql.PreparedStatement; // 安全绑定 SQL 参数。
import java.time.LocalDateTime; // 对应数据库的 UTC DATETIME(3) 时间。
import org.springframework.context.annotation.Profile; // skeleton 模式不加载数据库组件。
import org.springframework.jdbc.core.JdbcTemplate; // 沿用项目现有的 JDBC 工具。
import org.springframework.stereotype.Repository; // 将写入组件交给 Spring 管理。

@Profile("!skeleton") // 仅在正式数据库模式下启用。
@Repository // 供后续事件创建事务注入。
public class InitialDeliveryTaskRepository { // 只负责新事件的第 1 轮任务。
    private final JdbcTemplate jdbcTemplate; // 保存数据库访问工具。

    public InitialDeliveryTaskRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入现有 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate; // 供首轮任务插入使用。
    }

    public void insert(long eventPk, EndpointTaskSnapshot snapshot, int maxAttempts,
            BigDecimal retryDelay1Seconds, BigDecimal retryDelay2Seconds,
            LocalDateTime createdAt) { // 调用方提供事件主键、接收端及策略快照和统一 UTC 时间。
        int inserted = jdbcTemplate.update(connection -> { // 加入调用方已有事务，不在仓库中另开事务。
            PreparedStatement statement = connection.prepareStatement( // 创建参数化插入语句。
                    "INSERT INTO delivery_task (event_pk, round_no, status, attempt_count, "
                            + "max_attempts, retry_delay_1_s, retry_delay_2_s, next_run_at, "
                            + "target_type_snapshot, url_snapshot, timeout_ms_snapshot, "
                            + "secret_ciphertext_snapshot, created_at, updated_at) "
                            + "VALUES (?, 1, 'PENDING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" // 首轮尚未领取，立即到期。
            );
            statement.setLong(1, eventPk); // 关联同一事务中刚创建的事件主键。
            statement.setInt(2, maxAttempts); // 固定本轮允许的最大尝试次数。
            statement.setBigDecimal(3, retryDelay1Seconds); // 固定第一档重试等待秒数。
            statement.setBigDecimal(4, retryDelay2Seconds); // 固定第二档重试等待秒数。
            statement.setObject(5, createdAt); // 待执行时间与创建时间相同。
            statement.setString(6, snapshot.targetType().name()); // 复制目标类型。
            statement.setString(7, snapshot.url()); // 外部目标复制 URL；模拟器目标为 null。
            statement.setInt(8, snapshot.timeoutMs()); // 复制请求超时毫秒数。
            statement.setBytes(9, snapshot.secretCiphertextSnapshot()); // 只复制密文，不解密或保存明文。
            statement.setObject(10, createdAt); // 写入统一的 UTC 创建时间。
            statement.setObject(11, createdAt); // 新建任务的更新时间与创建时间相同。
            return statement; // 交给 JdbcTemplate 执行。
        });
        if (inserted != 1) { // 必须恰好写入一条首轮任务。
            throw new IllegalStateException("首轮投递任务存储失败"); // 让调用方事务回滚事件和任务。
        }
    }
}