package com.hookflow.auth.api; // 业务 Key 轮换的 HTTP 接口属于认证模块 API 层。

import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.http.HttpStatus; // 指定非法请求的 HTTP 状态。
import org.springframework.http.MediaType; // 声明成功响应为 JSON。
import org.springframework.web.bind.annotation.PostMapping; // 声明 POST 路由。
import org.springframework.web.bind.annotation.RequestBody; // 检查本接口不应携带的请求体。
import org.springframework.web.bind.annotation.RestController; // 注册 JSON Controller。
import org.springframework.web.server.ResponseStatusException; // 交给现有统一错误处理器生成 400 响应。
import com.hookflow.auth.application.BusinessApiKeyService; // 调用业务 Key 轮换事务。
import java.security.Principal; // 读取安全过滤器建立的当前管理员身份。
import jakarta.servlet.http.HttpServletRequest; // 读取当前请求中服务端保存的关联 ID。
import com.hookflow.config.RequestIdFilter; // 使用现有请求 ID 属性名称。

@Profile("!skeleton") // 无数据库配置不注册轮换接口。
@RestController // 将此类注册为正式 HTTP Controller。
public class BusinessApiKeyController { // 提供管理员专用的 Key 轮换接口。
    private final BusinessApiKeyService apiKeys; // 保存轮换应用服务。

    public BusinessApiKeyController(BusinessApiKeyService apiKeys) { // 由 Spring 注入应用服务。
        this.apiKeys = apiKeys; // 保存服务，供接口方法调用。
    } // 构造方法结束。

    @PostMapping(path = "/api/v1/admin/api-key/rotate", produces = MediaType.APPLICATION_JSON_VALUE) // 只为此路径注册 POST 接口，成功返回 JSON。
    public RotateResult rotate(@RequestBody(required = false) String body, Principal principal, HttpServletRequest request) { // 接收请求体、已认证身份和当前请求。
        if (body != null && !body.isBlank()) { // 本接口不接受非空请求体。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 失败请求在进入轮换事务前被拒绝，不产生成功审计。
        } // 请求体检查结束。
        long actorUserId = Long.parseLong(principal.getName()); // 管理员过滤器将已验证的数据库用户 ID 设置为身份名称。
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); // 读取服务端生成的 ID，不信任客户端同名请求头。
        return new RotateResult(apiKeys.rotate(actorUserId, requestId)); // 轮换和审计共同提交后才返回新 Key 明文。
    } // 轮换接口结束。

    public record RotateResult(String apiKey) { // 成功响应严格只包含 apiKey 字段。
    } // 响应类型结束。
} // 轮换 Controller 结束。