package com.hookflow.event.application; // 事件提交的事务间编排属于应用层。

import com.hookflow.endpoint.application.EndpointTaskSnapshotService.EndpointDisabledException; // 区分停用接收端。
import com.hookflow.event.application.EventCreationService.CreatedEvent; // 首次创建的结果。
import com.hookflow.event.domain.CanonicalEventPayload; // 本次请求的规范化内容。
import com.hookflow.event.infrastructure.EventRepository.ExistingEvent; // 已保存事件的字段。
import java.time.Instant; // 响应使用 UTC 时间点。
import java.time.ZoneOffset; // 将数据库 UTC 时间转换为时间点。
import java.util.Optional; // 表示某次查询可能没有找到事件。
import org.springframework.context.annotation.Profile; // skeleton 模式不加载数据库服务。
import org.springframework.dao.DuplicateKeyException; // 识别唯一键插入竞争。
import org.springframework.http.HttpStatus; // 接收端不存在时返回 404。
import org.springframework.stereotype.Service; // 注册独立的提交编排服务。
import org.springframework.web.server.ResponseStatusException; // 复用统一的 HTTP 错误处理。

@Profile("!skeleton") // 仅正式数据库模式启用。
@Service // 由控制器调用；此类本身不持有数据库事务。
public class EventSubmissionService { // 协调已有事件查询、首次创建和竞争后的重读。
    private final ExistingEventLookupService lookup; // 每次调用都通过代理开启只读事务。
    private final EventCreationService creation; // 首次创建使用独立的写事务。

    public EventSubmissionService(ExistingEventLookupService lookup,
            EventCreationService creation) { // 由 Spring 注入两个事务入口。
        this.lookup = lookup; // 用于提交前查询及失败后重新查询。
        this.creation = creation; // 用于插入事件和首轮任务。
    }

    public SubmissionResult submit(String eventId, String eventType,
            long endpointId, CanonicalEventPayload payload) { // 控制器先完成字段校验。
        Optional<ExistingEvent> before = lookup.findMatching(
                eventId, eventType, endpointId, payload); // 先判断已存在的 ID，不受当前接收端配置影响。
        if (before.isPresent()) { // 完全相同的既有事件。
            return duplicate(before.orElseThrow()); // 返回原事件，不进入创建事务。
        }

        try { // 只有初次查询未发现事件时才尝试创建。
            Optional<CreatedEvent> created = creation.create(
                    eventId, eventType, endpointId, payload); // 内部事务锁接收端并原子写入事件与任务。
            if (created.isPresent()) { // 首次创建的事务已成功提交。
                CreatedEvent event = created.orElseThrow(); // 取得已提交事件的摘要。
                return new SubmissionResult(event.eventId(), event.eventType(),
                        event.endpointId(), "PENDING", 1, event.createdAt(), false); // 返回首次接收结果。
            }
            return lookup.findMatching(eventId, eventType, endpointId, payload)
                    .map(this::duplicate) // 接收端不存在期间若有获胜提交，仍按事件 ID 判定。
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // 确无事件且接收端不存在。
        } catch (DuplicateKeyException exception) { // 唯一键竞争使创建事务回滚后才会到达这里。
            return lookup.findMatching(eventId, eventType, endpointId, payload)
                    .map(this::duplicate) // 新只读事务读取获胜记录；不同内容由查找服务抛出 409。
                    .orElseThrow(() -> exception); // 若查不到获胜记录，不把其他数据库故障伪装成重复。
        } catch (EndpointDisabledException exception) { // 等待接收端锁期间可能已有事件提交、随后接收端被停用。
            return lookup.findMatching(eventId, eventType, endpointId, payload)
                    .map(this::duplicate) // 既有事件仍按四元组返回 200 或 409。
                    .orElseThrow(() -> exception); // 真正的新事件仍按原契约返回停用错误。
        }
    }

    private SubmissionResult duplicate(ExistingEvent event) { // 从已保存事件构造重复响应。
        return new SubmissionResult(event.eventId(), event.eventType(),
                event.endpointId(), event.currentStatus(), event.latestRoundNo(),
                event.createdAt().toInstant(ZoneOffset.UTC), true); // 沿用原创建时间和此次读取的最新轮信息。
    }

    public record SubmissionResult(String eventId, String eventType, long endpointId,
            String status, int latestRoundNo, Instant createdAt,
            boolean duplicate) { // 只包含事件提交响应所需的非敏感字段。
    }
}