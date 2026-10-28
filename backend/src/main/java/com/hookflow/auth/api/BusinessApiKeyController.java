package com.hookflow.auth.api; // 业务 Key 轮换的 HTTP 接口属于认证模块 API 层。

import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.http.HttpStatus; // 指定非法请求的 HTTP 状态。
import org.springframework.http.MediaType; // 声明成功响应为 JSON。
import org.springframework.web.bind.annotation.PostMapping; // 声明 POST 路由。
import org.springframework.web.bind.annotation.RequestBody; // 检查本接口不应携带的请求体。
import org.springframework.web.bind.annotation.RestController; // 注册 JSON Controller。
import org.springframework.web.server.ResponseStatusException; // 交给现有统一错误处理器生成 400 响应。
import com.hookflow.auth.application.BusinessApiKeyService; // 调用业务 Key 轮换事务。

@Profile("!skeleton") // 无数据库配置不注册轮换接口。
@RestController // 将此类注册为正式 HTTP Controller。
public class BusinessApiKeyController { // 提供管理员专用的 Key 轮换接口。
    private final BusinessApiKeyService apiKeys; // 保存轮换应用服务。

    public BusinessApiKeyController(BusinessApiKeyService apiKeys) { // 由 Spring 注入应用服务。
        this.apiKeys = apiKeys; // 保存服务，供接口方法调用。
    } // 构造方法结束。

    @PostMapping(path = "/api/v1/admin/api-key/rotate", produces = MediaType.APPLICATION_JSON_VALUE) // 只为此路径注册 POST 接口，成功返回 JSON。
    public RotateResult rotate(@RequestBody(required = false) String body) { // 本接口不需要请求体。
        if (body != null && !body.isBlank()) { // 拒绝传入字段或其他非空内容。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 使用统一错误结构返回 400，不回显输入。
        } // 请求体检查结束。
        return new RotateResult(apiKeys.rotate()); // 事务成功提交后，才把新 Key 放入本次响应。
    } // 轮换接口结束。

    public record RotateResult(String apiKey) { // 成功响应严格只包含 apiKey 字段。
    } // 响应类型结束。
} // 轮换 Controller 结束。