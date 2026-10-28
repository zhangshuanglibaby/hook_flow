package com.hookflow.event.api; // 管理员测试事件入口属于事件 API 层。

import com.hookflow.event.application.EventSubmissionService; // 复用业务事件的提交用例。
import com.hookflow.event.application.EventSubmissionService.SubmissionResult; // 读取提交后的事件摘要。
import jakarta.servlet.http.HttpServletRequest; // 从请求输入流读取完整请求体。
import java.io.IOException; // 声明取得请求输入流时可能发生的 I/O 错误。
import org.springframework.context.annotation.Profile; // 在无数据库的 skeleton 模式下不注册此入口。
import org.springframework.http.HttpStatus; // 新建测试事件按契约返回 HTTP 202。
import org.springframework.http.MediaType; // 限定请求和响应的 JSON 类型。
import org.springframework.http.ResponseEntity; // 返回状态码及事件摘要。
import org.springframework.web.bind.annotation.PostMapping; // 注册管理员测试事件 POST 路由。
import org.springframework.web.bind.annotation.RestController; // 由 Spring MVC 输出 JSON 响应。

@Profile("!skeleton") // 仅在正式数据库模式启用测试事件入口。
@RestController // 将此类注册为 HTTP 控制器。
public class AdminTestEventController { // 只负责解析与映射响应，不开启数据库事务。
    private final EventRequestJsonParser parser = new EventRequestJsonParser(); // 复用业务入口的大小限制、严格 JSON 解析及 payload 规范化。
    private final EventSubmissionService submission; // 复用既有的去重和创建用例。

    public AdminTestEventController(EventSubmissionService submission) { // 由 Spring 注入现有应用服务。
        this.submission = submission; // 保存服务供请求处理方法调用。
    }

    @PostMapping(path = "/api/v1/admin/events/test", // 此路径沿用现有 /api/v1/admin/** 的管理员权限规则。
            consumes = MediaType.APPLICATION_JSON_VALUE, // 请求体必须是 JSON。
            produces = MediaType.APPLICATION_JSON_VALUE) // 响应体使用 JSON。
    public ResponseEntity<EventSubmissionController.EventView> submitTest(
            HttpServletRequest request) throws IOException { // HTTP 层本身不持有数据库事务。
        EventRequestJsonParser.ParsedEventJson parsed = parser.parse(
                request.getInputStream()); // 与业务入口一样限制整个请求体并规范化 payload。
        EventSubmissionInput input = EventSubmissionInput.fromTest(parsed); // 拒绝 eventId 和未知字段，并复用字段校验。
        SubmissionResult result = submission.submit(input.eventId(), input.eventType(),
                input.endpointId(), input.payload()); // 由同一用例检查接收端并在既有事务规则内创建事件和首轮任务。
        EventSubmissionController.EventView body = new EventSubmissionController.EventView(
                result.eventId(), result.eventType(), result.endpointId(),
                result.status(), result.latestRoundNo(), result.createdAt(),
                result.duplicate()); // 复用业务入口的七字段事件响应结构。
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body); // 新生成 ID 的测试事件返回 202。
    }
}