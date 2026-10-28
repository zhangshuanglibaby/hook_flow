package com.hookflow.auth.api; // 登录接口属于认证模块的 HTTP 层。

import java.util.Map; // 将 JSON 对象作为结构化字段集合读取。
import org.springframework.context.annotation.Profile; // 无数据库的 skeleton 配置不注册登录接口。
import org.springframework.http.HttpStatus; // 指定参数错误的 HTTP 状态。
import org.springframework.http.MediaType; // 限定请求和响应使用 JSON。
import org.springframework.web.bind.annotation.PostMapping; // 声明登录 POST 路由。
import org.springframework.web.bind.annotation.RequestBody; // 从请求体读取 JSON 对象。
import org.springframework.web.bind.annotation.RestController; // 返回 JSON 响应。
import org.springframework.web.server.ResponseStatusException; // 让现有统一异常处理器生成 400 错误体。
import com.hookflow.auth.application.AdminLoginService; // 调用已验证的登录服务。

@Profile("!skeleton") // skeleton 冒烟测试没有数据库，不创建此控制器。
@RestController // 由 Spring MVC 注册 JSON 接口。
public class AuthController { // 仅提供 P2-01 的管理员登录入口。
    private final AdminLoginService loginService; // 保存登录应用服务。

    public AuthController(AdminLoginService loginService) { // 由 Spring 注入真实登录流程。
        this.loginService = loginService; // 后续请求共用该服务。
    } // 构造方法结束。

    @PostMapping(path = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 只接受 JSON POST，并返回 JSON。
    public AdminLoginService.LoginResult login(@RequestBody Map<String, Object> request) { // 解析请求字段并返回 Token 与到期时间。
        if (request == null || request.size() != 2 || !request.containsKey("username") || !request.containsKey("password")) { // 拒绝缺失字段或任何未知字段。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 交由统一处理器返回 INVALID_REQUEST 和 requestId。
        } // 字段集合检查结束。
        if (!(request.get("username") instanceof String username) || !(request.get("password") instanceof String password)) { // 两个字段都必须是 JSON 字符串。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝 null、数字、数组和对象等错误类型。
        } // 字段类型检查结束。
        if (username.isBlank() || username.codePointCount(0, username.length()) > 64 // 用户名必须是 1～64 个非全空白 Unicode 码点。
                || password.isBlank() || password.codePointCount(0, password.length()) > 1024) { // 密码必须是 1～1024 个非全空白 Unicode 码点。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 不在错误消息中回显凭据。
        } // 字段长度及空白检查结束。
        return loginService.login(username, password); // 凭据错误交由现有 401 映射；成功返回本次 Token。
    } // 登录接口结束。
} // 控制器结束。