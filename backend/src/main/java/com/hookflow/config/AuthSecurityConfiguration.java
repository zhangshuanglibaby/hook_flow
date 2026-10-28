package com.hookflow.config; // 将全局 HTTP 安全边界放在共用配置层。

import java.io.IOException; // 写入安全错误响应时可能抛出输入输出异常。
import java.nio.charset.StandardCharsets; // 将错误响应明确设置为 UTF-8 编码。
import java.util.UUID; // 请求缺少 requestId 属性时生成兜底关联 ID。

import jakarta.servlet.http.HttpServletRequest; // 读取当前请求及其 requestId 属性。
import jakarta.servlet.http.HttpServletResponse; // 写入 401 或 403 错误响应。

import org.springframework.beans.factory.ObjectProvider; // 兼容无数据库的 skeleton 测试配置。
import org.springframework.context.annotation.Bean; // 注册 Spring Bean。
import org.springframework.context.annotation.Configuration; // 声明 Spring 配置类。
import org.springframework.http.HttpMethod; // 精确限定公开接口的 HTTP 方法。
import org.springframework.http.MediaType; // 设置统一 JSON 错误响应的媒体类型。
import org.springframework.security.config.annotation.web.builders.HttpSecurity; // 配置 Spring Security HTTP 安全规则。
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer; // 关闭不使用的默认认证方式。
import org.springframework.security.config.http.SessionCreationPolicy; // 配置无状态安全策略。
import org.springframework.security.core.AuthenticationException; // 表示请求没有通过认证。
import org.springframework.security.web.AuthenticationEntryPoint; // 处理 401 未认证响应。
import org.springframework.security.web.SecurityFilterChain; // 表示实际执行的安全过滤链。
import org.springframework.security.web.access.AccessDeniedHandler; // 处理 403 无权限响应。
import org.springframework.security.web.access.intercept.AuthorizationFilter; // 作为管理员 Token 过滤器的插入位置。

import tools.jackson.databind.json.JsonMapper; // 使用 Spring Boot 4 提供的 JSON 映射器。

import com.hookflow.auth.application.AdminLoginService; // 提供现有的管理员 Token 数据库校验能力。
import com.hookflow.auth.infrastructure.AdminTokenAuthenticationFilter; // 解析并校验管理员 Bearer Token。
import com.hookflow.common.api.ApiError; // 统一的错误响应结构。
import com.hookflow.config.RequestIdFilter; // 读取当前请求已经生成的 requestId。

@Configuration(proxyBeanMethods = false) // 注册安全配置，并避免不必要的配置类代理。
public class AuthSecurityConfiguration { // 定义 HookFlow 的 HTTP 安全边界。

    private static final String REQUEST_ID_HEADER = "X-Request-Id"; // 对外返回请求关联 ID 的响应头名称。

    @Bean // 将安全过滤链交给 Spring Security 管理。
    public SecurityFilterChain securityFilterChain( // 创建应用唯一的无状态安全过滤链。
            HttpSecurity http, // Spring Security 提供的 HTTP 配置入口。
            ObjectProvider<AdminLoginService> loginServiceProvider, // 在正式配置中取得 Token 校验服务。
            JsonMapper jsonMapper // 序列化统一 401/403 错误体。
    ) throws Exception { // 安全链构建过程可能抛出配置异常。

        http.csrf(csrf -> csrf.disable()); // 当前 API 使用无状态 JSON 请求，不依赖浏览器 CSRF Token。

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)); // 不使用服务端 HTTP Session 保存登录状态。

        http.formLogin(AbstractHttpConfigurer::disable); // 不暴露 Spring Security 默认 HTML 登录页面。

        http.httpBasic(AbstractHttpConfigurer::disable); // 不接受 Basic 用户名密码认证，避免绕过项目登录接口。

        http.logout(AbstractHttpConfigurer::disable); // 不启用框架默认登出路径，项目登出由正式 Controller 处理。

        AdminLoginService loginService = loginServiceProvider.getIfAvailable(); // skeleton 配置没有数据库服务，正式配置才会取得登录服务。

        if (loginService != null) { // 只有正式数据库配置存在时才接入管理员 Token 过滤器。
            http.addFilterBefore( // 将管理员身份建立放在授权判断之前。
                    new AdminTokenAuthenticationFilter(loginService), // 创建只服务于当前安全链的管理员 Token 过滤器。
                    AuthorizationFilter.class // 确保授权规则执行前已经完成 Token 校验。
            ); // 管理员 Token 过滤器接入结束。
        } // 正式配置与 skeleton 配置的差异处理结束。

        http.exceptionHandling(exceptionHandling -> exceptionHandling // 配置安全层直接产生的 401 和 403 响应。
                .authenticationEntryPoint(authenticationEntryPoint(jsonMapper)) // 缺少或无效凭据时返回统一 401。
                .accessDeniedHandler(accessDeniedHandler(jsonMapper))); // 已认证但权限不足时返回统一 403。

        http.authorizeHttpRequests(authorize -> authorize // 按路径和角色配置访问权限。
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll() // 登录接口必须允许未认证请求。
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll() // 最小健康检查保持公开。
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").hasRole("ADMIN") // 登出只允许当前管理员 Token 调用。
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN") // 所有管理接口只允许管理员角色。
                .anyRequest().denyAll()); // 尚未实现或未明确授权的路径继续拒绝访问。

        return http.build(); // 构造并返回最终安全过滤链。
    } // 安全过滤链配置结束。

    private AuthenticationEntryPoint authenticationEntryPoint(JsonMapper jsonMapper) { // 创建统一处理 401 的安全入口点。
        return (request, response, exception) -> writeSecurityError( // 收到未认证请求时写入契约规定的 JSON 错误体。
                request, // 传入当前请求以取得 requestId。
                response, // 传入当前响应以写入状态和 JSON。
                jsonMapper, // 使用应用统一 JSON 映射器序列化错误体。
                HttpServletResponse.SC_UNAUTHORIZED, // 将 HTTP 状态设置为 401。
                "UNAUTHORIZED", // 使用 P0-02 约定的稳定错误代码。
                "未提供有效的管理员 Token" // 不回显 Token 内容，也不泄露数据库校验细节。
        ); // 401 错误响应创建结束。
    } // 401 入口点创建结束。

    private AccessDeniedHandler accessDeniedHandler(JsonMapper jsonMapper) { // 创建统一处理 403 的权限拒绝处理器。
        return (request, response, exception) -> writeSecurityError( // 收到已认证但无权限请求时写入 JSON 错误体。
                request, // 传入当前请求以取得 requestId。
                response, // 传入当前响应以写入状态和 JSON。
                jsonMapper, // 使用应用统一 JSON 映射器序列化错误体。
                HttpServletResponse.SC_FORBIDDEN, // 将 HTTP 状态设置为 403。
                "FORBIDDEN", // 使用 P0-02 约定的稳定错误代码。
                "当前凭据无权访问此接口" // 不说明内部权限判断细节，也不回显凭据。
        ); // 403 错误响应创建结束。
    } // 403 处理器创建结束。

    private static void writeSecurityError( // 写入安全过滤链产生的统一错误响应。
            HttpServletRequest request, // 当前 HTTP 请求。
            HttpServletResponse response, // 当前 HTTP 响应。
            JsonMapper jsonMapper, // 应用统一 JSON 映射器。
            int status, // 要返回的 HTTP 状态码。
            String code, // 对外稳定错误代码。
            String message // 对外安全中文错误消息。
    ) throws IOException { // JSON 序列化或响应写入失败时向 Servlet 容器抛出异常。

        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); // 读取 RequestIdFilter 已生成的请求关联 ID。

        String requestId = attribute instanceof String id // 判断过滤器是否已经写入了有效 requestId。
                ? id // 正常情况下复用同一个 requestId。
                : UUID.randomUUID().toString(); // 极端情况下生成非空兜底 requestId。

        response.setStatus(status); // 设置 401 或 403 HTTP 状态码。

        response.setHeader(REQUEST_ID_HEADER, requestId); // 确保安全层错误响应也携带 requestId 响应头。

        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // 确保中文错误消息使用 UTF-8 编码。

        response.setContentType(MediaType.APPLICATION_JSON_VALUE); // 将响应声明为 application/json。

        response.getWriter().write(jsonMapper.writeValueAsString(new ApiError(code, message, requestId))); // 按统一结构写入 code、message 和 requestId。
    } // 安全错误响应写入结束。
} // 安全配置类结束。