package com.hookflow.event.api; // 业务事件提交接口属于事件 API 层。

import com.hookflow.event.application.EventSubmissionService; // 调用去重与创建的应用层编排服务。
import com.hookflow.event.application.EventSubmissionService.SubmissionResult; // 读取提交后的事件摘要。
import jakarta.servlet.http.HttpServletRequest; // 从输入流读取请求体以执行大小限制。
import java.io.IOException; // 声明获取请求输入流可能发生的 I/O 错误。
import java.time.Instant; // 响应中的创建时间使用 UTC 时间点。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 模式。
import org.springframework.http.HttpStatus; // 根据是否重复选择 200 或 202。
import org.springframework.http.MediaType; // 限定请求和响应为 JSON。
import org.springframework.http.ResponseEntity; // 同时返回 HTTP 状态与响应体。
import org.springframework.web.bind.annotation.PostMapping; // 注册业务事件 POST 路由。
import org.springframework.web.bind.annotation.RestController; // 由 Spring MVC 输出 JSON。

@Profile("!skeleton") // 只有正式数据库模式才开放事件提交入口。
@RestController // 将该类注册为业务事件控制器。
public class EventSubmissionController { // 只处理 HTTP 解析、响应映射，不持有事务。
    private final EventRequestJsonParser parser = new EventRequestJsonParser(); // 复用请求体限制和规范化解析。
    private final EventSubmissionService submission; // 通过应用层编排独立的读、写事务。

    public EventSubmissionController(EventSubmissionService submission) { // 由 Spring 注入编排服务。
        this.submission = submission; // 供请求处理方法调用。
    }

    @PostMapping(path = "/api/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE) // 业务 Key 权限仍由既有安全配置检查。
    public ResponseEntity<EventView> submit(HttpServletRequest request) throws IOException { // 控制器本身不开始数据库事务。
        EventRequestJsonParser.ParsedEventJson parsed = parser.parse(
                request.getInputStream()); // 限制整个请求体并规范化 payload。
        EventSubmissionInput input = EventSubmissionInput.from(parsed); // 校验字段、类型、长度及未知字段。
        SubmissionResult result = submission.submit(input.eventId(), input.eventType(),
                input.endpointId(), input.payload()); // 判断重复或在独立写事务内创建事件和任务。
        EventView body = new EventView(result.eventId(), result.eventType(),
                result.endpointId(), result.status(), result.latestRoundNo(),
                result.createdAt(), result.duplicate()); // 沿用原创建时间，重复时带最新轮状态。
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED; // 重复 200，首次 202。
        return ResponseEntity.status(status).body(body); // 所有数据库事务结束后再返回响应。
    }

    public record EventView(String eventId, String eventType, long endpointId,
            String status, int latestRoundNo, Instant createdAt,
            boolean duplicate) { // 只输出契约规定的非敏感事件摘要字段。
    }
}