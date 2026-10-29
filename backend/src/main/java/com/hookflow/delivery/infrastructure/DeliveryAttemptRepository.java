package com.hookflow.delivery.infrastructure; // 投递尝试的数据库操作属于 delivery 基础设施层。

import com.hookflow.delivery.domain.DeliveryAttemptClassification.AttemptResult; // 使用已约定的尝试结果枚举。
import java.time.LocalDateTime; // 接收调用方生成的 UTC 毫秒时间。
import java.util.Objects; // 检查必需参数。
import java.util.Optional; // 表示尝试可能已被其他事务结束。
import org.springframework.context.annotation.Profile; // 无数据库的 skeleton 配置不加载此仓库。
import org.springframework.jdbc.core.JdbcTemplate; // 沿用项目已有的 JDBC 数据访问方式。
import org.springframework.stereotype.Repository; // 将仓库注册为 Spring 组件。
import org.springframework.transaction.annotation.Propagation; // 要求写操作加入调用方事务。
import org.springframework.transaction.annotation.Transactional; // 声明事务参与约束。

@Profile("!skeleton") // 骨架测试不创建数据库仓库。
@Repository // 由 Spring 管理，供领取和完成应用服务调用。
public class DeliveryAttemptRepository { // 只负责 delivery_attempt 表的持久化操作。
    private final JdbcTemplate jdbcTemplate; // 使用调用方事务绑定的数据库连接。

    public DeliveryAttemptRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入现有 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate;
    } // 仓库构造结束。

     @Transactional(propagation = Propagation.MANDATORY) // 没有外层领取事务时拒绝插入。
    public void insertStarted(long taskId, int attemptNo, LocalDateTime startedAt,
            String requestId) { // 参数由领取服务在同一事务中确定。
        if (taskId <= 0 || attemptNo < 1 || attemptNo > 3) { // 与数据库尝试序号约束一致。
            throw new IllegalArgumentException("投递尝试标识无效"); // 拒绝明显不合法的内部调用。
        } // 标识检查结束。
        Objects.requireNonNull(startedAt, "尝试开始时间缺失"); // 不使用数据库 NOW() 替代统一时钟。
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) { // 符合数据库列的限制。
            throw new IllegalArgumentException("投递请求 ID 无效"); // 不写入缺失或过长的关联 ID。
        } // 请求 ID 检查结束。
        int inserted = jdbcTemplate.update( // 插入失败时由外层领取事务整体回滚。
                "INSERT INTO delivery_attempt (task_id, attempt_no, result, started_at, request_id) "
                        + "VALUES (?, ?, 'STARTED', ?, ?)", // 未结束字段保持 NULL。
                taskId, attemptNo, startedAt, requestId // 逐项绑定参数，不拼接 SQL。
        ); // 执行唯一序号约束保护下的插入。
        if (inserted != 1) { // 成功领取必须恰好留下本序号的一条尝试。
            throw new IllegalStateException("投递尝试存储失败"); // 阻止不完整领取提交。
        } // 插入结果检查结束。
    } // STARTED 插入结束。

    @Transactional(propagation = Propagation.MANDATORY) // 必须在完成事务已锁定任务之后调用。
    public Optional<LockedAttempt> lockStarted(long taskId, int attemptNo) { // 锁定对应且尚未结束的尝试。
        if (taskId <= 0 || attemptNo < 1 || attemptNo > 3) { // 拒绝不合法的内部标识。
            return Optional.empty(); // 不对无效标识查询或写入。
        } // 标识检查结束。
        return jdbcTemplate.query( // 使用当前事务连接取得尝试行锁。
                "SELECT id, task_id, attempt_no, started_at "
                        + "FROM delivery_attempt "
                        + "WHERE task_id = ? AND attempt_no = ? AND result = 'STARTED' "
                        + "FOR UPDATE", // 必须在 endpoint、event、task 行锁之后执行。
                (rs, rowNum) -> new LockedAttempt( // 保存实际锁定行的身份和开始时间。
                        rs.getLong("id"), // 尝试内部主键。
                        rs.getLong("task_id"), // 尝试所属任务。
                        rs.getInt("attempt_no"), // 本轮尝试序号。
                        rs.getObject("started_at", LocalDateTime.class) // 用于核对结束时间不早于开始。
                ), // 单行映射结束。
                taskId, attemptNo // 按任务及序号参数化查询。
        ).stream().findFirst(); // 已结束或不存在时不返回可写对象。
    } // 持锁读取结束。

     @Transactional(propagation = Propagation.MANDATORY) // 与任务及最新轮事件状态更新共用一个完成事务。
    public int finish(LockedAttempt attempt, SingleDeliveryHttpResult result,
            LocalDateTime endedAt, long durationMs) { // 只结束本次持锁的 STARTED 尝试。
        Objects.requireNonNull(attempt, "持锁尝试缺失"); // 没有实际锁定行不得写回。
        Objects.requireNonNull(result, "单次投递结果缺失"); // 结果必须来自已校验的结果对象。
        Objects.requireNonNull(endedAt, "尝试结束时间缺失"); // 时间由调用方统一 UTC 时钟提供。
        if (endedAt.isBefore(attempt.startedAt()) || durationMs < 0) { // 遵守 V1 时间和耗时约束。
            throw new IllegalArgumentException("投递尝试结束时间或耗时无效"); // 不悄悄篡改时钟结果。
        } // 时间检查结束。

        AttemptResult classification = result.classification().result(); // 读取 P5 的结果分类。
        String errorCode = switch (classification) { // 只从固定枚举产生安全错误代码。
            case SUCCESS -> null; // 成功没有错误代码。
            case HTTP_FAILURE -> "HTTP_NON_SUCCESS"; // 已收到非 2xx 最终响应。
            case UNKNOWN -> "NETWORK_RESULT_UNKNOWN"; // 无法确认是否送达。
            case LOCAL_FAILURE -> "LOCAL_REQUEST_REJECTED"; // 明确在发送前被拒绝。
        }; // 固定错误代码选择结束。
        String errorMessage = switch (classification) { // 不读取或保存未经审查的异常文本。
            case SUCCESS -> null; // 成功没有错误说明。
            case HTTP_FAILURE -> "接收端返回非成功状态"; // 具体状态码另存于 http_status。
            case UNKNOWN -> "HTTP 结果未知"; // 不声称接收端未处理。
            case LOCAL_FAILURE -> "请求在发送前被拒绝"; // 不暴露 URL、密钥或内部异常。
        }; // 固定错误说明选择结束。

        return jdbcTemplate.update( // 只允许把这一条仍为 STARTED 的记录结束一次。
                "UPDATE delivery_attempt SET result = ?, ended_at = ?, duration_ms = ?, "
                        + "http_status = ?, response_excerpt = ?, error_code = ?, error_message = ? "
                        + "WHERE id = ? AND task_id = ? AND attempt_no = ? "
                        + "AND result = 'STARTED' AND ended_at IS NULL", // 迟到或重复结束不得覆盖结果。
                classification.name(), endedAt, durationMs, // 保存分类和结束时间、耗时。
                result.httpStatus(), result.responseExcerpt(), errorCode, errorMessage, // 保存受限结果字段。
                attempt.id(), attempt.taskId(), attempt.attemptNo() // 精确限定当前持锁的尝试。
        ); // 返回影响行数；完成服务须要求恰好为 1，否则回滚整个事务。
    } // 尝试条件写回结束。

   public record LockedAttempt(long id, long taskId, int attemptNo, // 保存已锁定尝试的主键、任务 ID 和序号。
            LocalDateTime startedAt) { // 保存 UTC 开始时间，供结束写回时核对。
    } // 持锁尝试数据定义结束。
} // 投递尝试仓库结束。