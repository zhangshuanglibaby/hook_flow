package com.hookflow.support; // 将测试专用 Controller 放在测试支持包中。

import java.util.Map; // 构造测试接口的 JSON 响应。

import org.springframework.context.annotation.Profile; // 只在集成测试配置组中启用。
import org.springframework.http.MediaType; // 声明测试响应为 JSON。
import org.springframework.web.bind.annotation.GetMapping; // 声明测试 GET 路由。
import org.springframework.web.bind.annotation.RestController; // 将测试类注册为 HTTP Controller。

@Profile("p2-login-test") // 仅在真实 MySQL 集成测试中启用，不进入正式运行配置。
@RestController // 注册一个只存在于测试代码中的 HTTP Controller。
public class ProtectedTestController { // 提供管理员权限边界测试端点。

    @GetMapping(path = "/api/v1/admin/test-protected", produces = MediaType.APPLICATION_JSON_VALUE) // 模拟一个受管理员保护的管理接口。
    public Map<String, Boolean> protectedEndpoint() { // 只有通过管理员授权后才能执行此方法。
        return Map.of("ok", true); // 返回简单 JSON，证明请求已经通过认证和权限判断。
    } // 测试端点方法结束。
} // 测试 Controller 结束。