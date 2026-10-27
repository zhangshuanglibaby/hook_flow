package com.hookflow.config; // 请求关联 ID 属于跨业务模块共用的配置设施。

import java.io.IOException; // 过滤器执行时可能抛出输入输出异常。
import java.util.UUID; // 为每次请求生成不可由客户端指定的随机 ID。
import jakarta.servlet.FilterChain; // 表示后续的过滤器及请求处理流程。
import jakarta.servlet.ServletException; // 表示 Servlet 处理异常。
import jakarta.servlet.http.HttpServletRequest; // 用于保存本次请求的 ID。
import jakarta.servlet.http.HttpServletResponse; // 用于把 ID 写入响应头。
import org.slf4j.MDC; // 在当前处理线程的日志上下文中保存 ID。
import org.springframework.core.Ordered; // 提供过滤器顺序常量。
import org.springframework.core.annotation.Order; // 指定过滤器执行顺序。
import org.springframework.stereotype.Component; // 将过滤器注册为 Spring 组件。
import org.springframework.web.filter.OncePerRequestFilter; // 每次请求分派执行一次的过滤器基类。

@Component // 让 Spring Boot 发现并注册这个过滤器。
@Order(Ordered.HIGHEST_PRECEDENCE) // 尽早建立 ID，供后续请求处理使用。
public final class RequestIdFilter extends OncePerRequestFilter { // 定义请求关联 ID 过滤器。
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId"; // 供后续错误映射从请求中读取同一个 ID。
    private static final String HEADER_NAME = "X-Request-Id"; // 响应头中使用的关联 ID 名称。
    private static final String MDC_KEY = "requestId"; // 结构化日志中使用的字段名称。

    @Override // 实现过滤器基类规定的请求处理方法。
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException { // 包围本次请求的后续处理。
        String requestId = UUID.randomUUID().toString(); // 始终由服务端生成，不信任客户端传来的同名请求头。
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId); // 保存到请求对象，供错误处理器读取。
        response.setHeader(HEADER_NAME, requestId); // 客户端可用响应头中的 ID 定位对应日志。
        MDC.put(MDC_KEY, requestId); // 让此线程处理请求期间产生的日志携带同一 ID。
        try { // 确保后续处理即使抛出异常也会清理日志上下文。
            filterChain.doFilter(request, response); // 继续执行认证、控制器等后续流程。
        } finally { // 无论请求成功还是失败都会执行清理。
            MDC.remove(MDC_KEY); // 防止容器复用线程时把本次 ID 带到下一次请求。
        } // 异常安全的处理范围结束。
    } // 过滤器方法结束。
} // 过滤器类结束。