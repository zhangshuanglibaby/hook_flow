package com.hookflow.support; // 将测试专用 Controller 放在测试支持包中。

import java.util.Map; // 构造两个测试端点的简单 JSON 响应。

import org.springframework.context.annotation.Profile; // 限定测试端点启用的配置组。
import org.springframework.http.MediaType; // 声明响应的 JSON 媒体类型。
import org.springframework.web.bind.annotation.GetMapping; // 声明管理员测试 GET 路由。
import org.springframework.web.bind.annotation.PostMapping; // 声明业务身份测试 POST 路由。
import org.springframework.web.bind.annotation.RestController; // 注册测试专用 HTTP Controller。

@Profile("p2-login-test") // 仅在指定集成测试配置组中启用。
@RestController // 让测试请求能够抵达实际的 Spring Security 过滤链。
public class ProtectedTestController { // 集中提供两种身份的测试端点。

    @GetMapping(path = "/api/v1/admin/test-protected", produces = MediaType.APPLICATION_JSON_VALUE) // 保留现有管理员测试端点。
    public Map<String, Boolean> protectedEndpoint() { // 仅用于确认管理员权限已通过。
        return Map.of("ok", true); // 返回简单结果，不执行管理业务逻辑。
    } // 管理员测试端点结束。

    @PostMapping(path = "/api/v1/events", produces = MediaType.APPLICATION_JSON_VALUE) // 仅在测试中模拟业务事件入口的路径。
    public Map<String, Boolean> businessProtectedEndpoint() { // 仅用于确认业务 Key 身份已通过。
        return Map.of("ok", true); // 返回测试结果，不保存、去重或投递事件。
    } // 业务身份测试端点结束。
} // 测试 Controller 结束。