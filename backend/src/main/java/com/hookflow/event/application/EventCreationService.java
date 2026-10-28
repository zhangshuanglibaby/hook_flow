package com.hookflow.event.application; // 新事件创建的事务协调属于事件应用层。

import com.hookflow.delivery.application.InitialDeliveryTaskService; // 通过投递应用层入口创建首轮任务。
import com.hookflow.endpoint.application.EndpointTaskSnapshotService; // 持锁取得已启用接收端的快照。
import com.hookflow.endpoint.domain.EndpointTaskSnapshot; // 保存本次创建使用的接收端配置。
import com.hookflow.event.domain.CanonicalEventPayload; // 接收 P4-01 的规范化内容和指纹。
import com.hookflow.event.infrastructure.EventRepository; // 写入事件模块自己的表。
import java.time.Clock; // 使用项目统一的可控 UTC 时钟。
import java.time.Instant; // 保存对外响应可使用的 UTC 创建时间。
import java.time.LocalDateTime; // 写入 MySQL DATETIME(3) 字段。
import java.time.ZoneOffset; // 明确按 UTC 转换数据库时间。
import java.time.temporal.ChronoUnit; // 将时间截取到毫秒精度。
import java.util.Optional; // 表示接收端可能不存在。
import org.springframework.context.annotation.Profile; // skeleton 模式不创建数据库服务。
import org.springframework.http.HttpStatus; // 指定非法内部调用的请求状态。
import org.springframework.stereotype.Service; // 注册事件创建用例。
import org.springframework.transaction.annotation.Isolation; // 采用约定的事务隔离级别。
import org.springframework.transaction.annotation.Transactional; // 将两次插入置于同一事务。
import org.springframework.web.server.ResponseStatusException; // 复用统一的 400 错误响应。

@Profile("!skeleton") // 正式数据库模式才启用创建服务。
@Service // 供后续事件控制器调用。
public class EventCreationService { // 协调锁、快照、事件和首轮任务。
    private final EndpointTaskSnapshotService endpoints; // 只通过接收端应用层入口取快照。
    private final EventRepository events; // 保存事件模块的仓库。
    private final InitialDeliveryTaskService tasks; // 只通过投递应用层入口创建任务。
    private final Clock clock; // 保证事务内使用同一个 UTC 时间来源。

    public EventCreationService(EndpointTaskSnapshotService endpoints,
            EventRepository events, InitialDeliveryTaskService tasks, Clock clock) { // 由 Spring 注入依赖。
        this.endpoints = endpoints; // 供事务开始后首先锁定接收端。
        this.events = events; // 供持锁后写入事件。
        this.tasks = tasks; // 供同一事务写入首轮任务。
        this.clock = clock; // 供本次创建取得一次时间。
    }

    @Transactional(isolation = Isolation.READ_COMMITTED) // 方法成功返回后，由事务代理提交事件和任务。
    public Optional<CreatedEvent> create(String eventId, String eventType,
            long endpointId, CanonicalEventPayload payload) { // 接收已规范化的事件输入。
        if (invalidText(eventId) || invalidText(eventType) || endpointId <= 0 || payload == null) { // 防止其他入口绕过基本校验。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 非法输入在写库前返回统一 400。
        }
        Optional<EndpointTaskSnapshot> snapshot = endpoints.lockEnabledSnapshot(endpointId); // 首先锁接收端并复查启用状态。
        if (snapshot.isEmpty()) { // 指定接收端不存在。
            return Optional.empty(); // 交由后续控制器映射为 404，不写任何事件。
        }
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS); // 本次事务只取得一次创建时间。
        LocalDateTime databaseTime = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC); // 按 UTC 转成数据库时间。
        long eventPk = events.insert(eventId, eventType, endpointId, payload, databaseTime); // 写入第 1 轮 PENDING 事件。
        tasks.createFirstRound(eventPk, snapshot.orElseThrow(), databaseTime); // 同事务复制配置并写入首轮任务。
        return Optional.of(new CreatedEvent(eventId, eventType, endpointId, createdAt)); // 仅返回创建结果，不在事务内投递。
    }

    private static boolean invalidText(String value) { // 两个事件标识字段遵守相同的基本约束。
        return value == null || value.isBlank() // 缺失或全空白均非法。
                || value.codePointCount(0, value.length()) > 128; // 按 Unicode 码点限制长度。
    }

    public record CreatedEvent(String eventId, String eventType, long endpointId,
            Instant createdAt) { // 保存首次创建响应所需的非敏感字段。
    }
}