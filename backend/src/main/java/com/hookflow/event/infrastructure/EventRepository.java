package com.hookflow.event.infrastructure; // 事件表的数据库操作放在基础设施层。

import com.hookflow.event.domain.CanonicalEventPayload; // 使用 P4-01 已生成的规范化内容和指纹。
import java.sql.PreparedStatement; // 通过预编译语句安全绑定插入参数。
import java.sql.Statement; // 请求数据库返回新事件的主键。
import java.time.LocalDateTime; // 对应数据库的 UTC DATETIME(3) 创建时间。
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
}