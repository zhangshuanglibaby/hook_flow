package com.hookflow.event.application; // 已有事件的去重判断属于事件应用层。

import com.hookflow.event.domain.CanonicalEventPayload; // 读取本次请求的规范化内容和指纹。
import com.hookflow.event.infrastructure.EventRepository; // 通过事件仓库读取已提交的记录。
import com.hookflow.event.infrastructure.EventRepository.ExistingEvent; // 使用仓库返回的事件字段。
import java.util.Arrays; // 按字节比较 SHA-256 指纹。
import java.util.Optional; // 区分不存在与已经存在的事件。
import org.springframework.context.annotation.Profile; // skeleton 模式不创建数据库服务。
import org.springframework.http.HttpStatus; // 内容冲突时使用 HTTP 409。
import org.springframework.stereotype.Service; // 注册为独立的 Spring 服务。
import org.springframework.transaction.annotation.Isolation; // 指定 READ_COMMITTED 隔离级别。
import org.springframework.transaction.annotation.Transactional; // 为本次读取开启独立的只读事务。
import org.springframework.web.server.ResponseStatusException; // 将四元组冲突交给统一错误处理器。

@Profile("!skeleton") // 无数据库的 skeleton 模式不加载此服务。
@Service // 后续由非事务的控制器调用 Spring 代理，确保读取有自己的事务。
public class ExistingEventLookupService { // 只负责查找及比较，不创建事件或任务。
    private final EventRepository events; // 保存事件表的读取入口。

    public ExistingEventLookupService(EventRepository events) { // 由 Spring 注入事件仓库。
        this.events = events; // 供本服务查询已提交事件。
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED) // 每次从非事务入口调用时读取最新已提交数据。
    public Optional<ExistingEvent> findMatching(String eventId, String eventType,
            long endpointId, CanonicalEventPayload payload) { // 输入必须先经过现有请求校验。
        return events.findByEventId(eventId).map(existing -> { // 没找到 ID 时返回空结果。
            boolean same = eventId.equals(existing.eventId()) // 核对事件 ID。
                    && eventType.equals(existing.eventType()) // 核对事件类型。
                    && endpointId == existing.endpointId() // 核对接收端 ID，而非当前接收端配置。
                    && Arrays.equals(payload.fingerprint(), existing.payloadFingerprint()) // 核对 SHA-256 指纹。
                    && payload.json().equals(existing.payloadJson()); // 即使指纹相同，也核对规范化 JSON 全文。
            if (!same) { // 四元组任一内容不同。
                throw new ResponseStatusException(HttpStatus.CONFLICT); // 返回 409，不创建任务或尝试。
            }
            return existing; // 完全相同，保留原创建时间及此次读取的最新状态、轮号。
        });
    }
}