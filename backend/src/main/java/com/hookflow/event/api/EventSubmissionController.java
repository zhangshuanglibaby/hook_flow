package com.hookflow.event.api; // 业务事件提交接口属于事件 API 层。

import com.hookflow.event.application.EventCreationService; // 调用同事务创建事件和首轮任务的应用服务。
import com.hookflow.event.application.EventCreationService.CreatedEvent; // 读取事务提交后的创建结果。
import jakarta.servlet.http.HttpServletRequest; // 按输入流读取完整请求体，执行 256 KiB 限制。
import java.io.IOException; // 声明获取请求输入流可能发生的 I/O 错误。
import java.time.Instant; // 使用 UTC 时间点作为响应创建时间。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 模式。
import org.springframework.http.HttpStatus; // 指定 202 成功和 404 不存在状态。
import org.springframework.http.MediaType; // 限定请求和响应为 JSON。
import org.springframework.http.ResponseEntity; // 同时返回 HTTP 状态和响应体。
import org.springframework.web.bind.annotation.PostMapping; // 注册业务事件 POST 路由。
import org.springframework.web.bind.annotation.RestController; // 由 Spring MVC 输出 JSON。
import org.springframework.web.server.ResponseStatusException; // 交给统一异常处理器生成 404 错误体。

@Profile("!skeleton") // 只有正式数据库模式才开放事件创建入口。
@RestController // 让 Spring MVC 管理业务事件 HTTP 请求。
public class EventSubmissionController { // 当前只实现首次创建，不处理 P4-03 的重复事件。
    private final EventRequestJsonParser parser = new EventRequestJsonParser(); // 复用 P4-01 的限流解析与规范化规则。
    private final EventCreationService creation; // 保存带 READ_COMMITTED 事务的创建服务。

    public EventSubmissionController(EventCreationService creation) { // 由 Spring 注入正式应用服务。
        this.creation = creation; // 请求处理时通过事务代理调用创建操作。
    } // 构造方法结束。

    @PostMapping(path = "/api/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE) // 业务 Key 权限由既有安全配置检查。
    public ResponseEntity<EventView> submit(HttpServletRequest request) throws IOException { // 直接读取请求流，不使用无限制的 @RequestBody。
        EventRequestJsonParser.ParsedEventJson parsed = parser.parse(request.getInputStream()); // 限制整个请求体并解析、规范化 payload。
        EventSubmissionInput input = EventSubmissionInput.from(parsed); // 校验必填字段、类型、长度及未知字段。
        CreatedEvent created = creation.create(input.eventId(), input.eventType(),
                input.endpointId(), input.payload()) // 在同一事务内锁接收端、写事件和首轮任务。
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // 接收端不存在时返回统一 404。
        EventView body = new EventView(created.eventId(), created.eventType(),
                created.endpointId(), "PENDING", 1, created.createdAt(), false); // 首次创建固定第 1 轮、非重复。
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body); // 创建事务已提交后才返回 202。
    } // 首次事件提交处理结束。

    public record EventView(String eventId, String eventType, long endpointId,
            String status, int latestRoundNo, Instant createdAt,
            boolean duplicate) { // 只输出契约规定的非敏感事件摘要字段。
    } // 响应结构定义结束。
} // 业务事件控制器结束。