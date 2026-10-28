package com.hookflow.auth.api; // 登录和退出接口属于认证模块的 HTTP 层。

import java.util.Map; // 将登录 JSON 请求体作为结构化字段集合读取。
import java.util.Optional; // 表示 Authorization 请求头中可能没有合法 Bearer Token。

import org.springframework.context.annotation.Profile; // 无数据库的 skeleton 配置不注册认证接口。
import org.springframework.http.HttpHeaders; // 提供 Authorization 请求头名称常量。
import org.springframework.http.HttpStatus; // 指定错误和成功响应状态。
import org.springframework.http.MediaType; // 限定登录接口使用 JSON。
import org.springframework.web.bind.annotation.PostMapping; // 声明 POST 路由。
import org.springframework.web.bind.annotation.RequestBody; // 从请求体读取登录字段。
import org.springframework.web.bind.annotation.RequestHeader; // 从请求头读取管理员 Token。
import org.springframework.web.bind.annotation.ResponseStatus; // 指定 logout 成功时返回 204。
import org.springframework.web.bind.annotation.RestController; // 注册 JSON Controller。
import org.springframework.web.server.ResponseStatusException; // 让现有统一异常处理器生成 400 错误体。

import com.hookflow.auth.application.AdminLoginService; // 调用登录和 Token 撤销应用服务。

@Profile("!skeleton") // skeleton 测试没有数据库，不创建认证 Controller。
@RestController // 由 Spring MVC 注册认证 HTTP 接口。
public class AuthController { // 提供管理员登录和退出接口。

    private static final String BEARER_PREFIX = "Bearer "; // 管理员 Token 必须使用的认证方案前缀。

    private final AdminLoginService loginService; // 保存登录和会话撤销应用服务。

    public AuthController(AdminLoginService loginService) { // 由 Spring 注入认证应用服务。
        this.loginService = loginService; // 保存服务供两个接口共用。
    } // 构造方法结束。

    @PostMapping(path = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 只接受 JSON 登录请求并返回 JSON。
    public AdminLoginService.LoginResult login(@RequestBody Map<String, Object> request) { // 解析登录字段并返回 Token 与到期时间。
        if (request == null || request.size() != 2 || !request.containsKey("username") || !request.containsKey("password")) { // 拒绝缺失字段或任何未知字段。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 交由统一处理器返回 400 错误体。
        } // 字段集合检查结束。

        if (!(request.get("username") instanceof String username) || !(request.get("password") instanceof String password)) { // 两个字段都必须是 JSON 字符串。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝 null、数字、数组和对象等错误类型。
        } // 字段类型检查结束。

        if (username.isBlank() || username.codePointCount(0, username.length()) > 64 // 用户名必须是 1～64 个非全空白 Unicode 码点。
                || password.isBlank() || password.codePointCount(0, password.length()) > 1024) { // 密码必须是 1～1024 个非全空白 Unicode 码点。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 不在错误消息中回显任何凭据。
        } // 字段长度及空白检查结束。

        return loginService.login(username, password); // 校验凭据并创建管理员会话。
    } // 登录接口结束。

    @PostMapping(path = "/api/v1/auth/logout") // 声明管理员退出接口；安全配置会要求 ROLE_ADMIN。
    @ResponseStatus(HttpStatus.NO_CONTENT) // 成功时返回 204，响应体保持为空。
    public void logout(@RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader) { // 从当前请求读取 Authorization 请求头。
        extractBearerToken(authorizationHeader).ifPresent(loginService::revokeToken); // 只把合法 Bearer Token 交给应用服务撤销。
    } // 退出接口结束。

    private static Optional<String> extractBearerToken(String authorizationHeader) { // 使用与认证过滤器相同的规则解析 Bearer Token。
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) { // 缺失请求头或认证方案不精确匹配时拒绝解析。
            return Optional.empty(); // 不把错误格式的值当作管理员 Token。
        } // 认证方案检查结束。

        String token = authorizationHeader.substring(BEARER_PREFIX.length()); // 去除 Bearer 前缀，取得 Token 文本。

        if (token.isBlank() || token.codePoints().anyMatch(Character::isWhitespace)) { // 拒绝空 Token 以及含空格、制表符或换行的 Token。
            return Optional.empty(); // 错误格式不会进入撤销数据库操作。
        } // Token 文本检查结束。

        return Optional.of(token); // 返回原始 Token，由应用服务计算 SHA-256 哈希。
    } // Bearer Token 解析方法结束。
} // 认证 Controller 结束。