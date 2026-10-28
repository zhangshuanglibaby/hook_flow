package com.hookflow.auth.infrastructure; // 业务 Key 的 HTTP 身份识别属于认证模块基础设施层。

import java.io.IOException; // 过滤器链可能抛出输入输出异常。
import java.util.List; // 构造业务角色权限列表。
import jakarta.servlet.FilterChain; // 表示后续安全过滤器和 Controller 的执行链。
import jakarta.servlet.ServletException; // 表示 Servlet 处理异常。
import jakarta.servlet.http.HttpServletRequest; // 读取当前请求的认证请求头。
import jakarta.servlet.http.HttpServletResponse; // 将响应交给后续过滤器处理。
import org.springframework.http.HttpHeaders; // 使用标准 Authorization 请求头名称。
import org.springframework.security.authentication.AnonymousAuthenticationToken; // 识别尚未建立有效身份的匿名认证。
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // 创建已认证的业务身份对象。
import org.springframework.security.core.authority.SimpleGrantedAuthority; // 授予独立的业务角色。
import org.springframework.security.core.context.SecurityContextHolder; // 保存当前请求的认证身份。
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource; // 附加标准请求来源信息。
import org.springframework.web.filter.OncePerRequestFilter; // 保证一次请求只执行一次本过滤逻辑。
import com.hookflow.auth.application.BusinessApiKeyService; // 调用业务 Key 数据库校验能力。

public final class BusinessApiKeyAuthenticationFilter extends OncePerRequestFilter { // 定义业务 Key 身份过滤器。
    private static final String KEY_HEADER = "X-HookFlow-Api-Key"; // 使用 P0-02 契约指定的业务请求头。
    private static final String BUSINESS_ROLE = "ROLE_BUSINESS"; // 与管理员角色分开的业务权限名称。
    private final BusinessApiKeyService apiKeys; // 保存 Key 校验应用服务。

    public BusinessApiKeyAuthenticationFilter(BusinessApiKeyService apiKeys) { // 由安全配置创建并传入校验服务。
        this.apiKeys = apiKeys; // 保存服务，供每次请求验证 Key。
    } // 构造方法结束。

    @Override // 实现单次请求的过滤处理。
    protected void doFilterInternal( // 检查当前请求能否建立业务身份。
            HttpServletRequest request, // 提供请求头和请求来源信息。
            HttpServletResponse response, // 传给后续过滤器，不在这里直接写错误体。
            FilterChain filterChain // 后续安全授权和 MVC 处理链。
    ) throws ServletException, IOException { // 保持 Servlet 过滤器规定的异常类型。
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) { // 只在没有管理员认证头时考虑业务 Key，避免混用两类凭据。
            var values = request.getHeaders(KEY_HEADER); // 读取该请求头的全部值，防止重复请求头产生歧义。
            if (values.hasMoreElements()) { // 只有请求确实携带业务 Key 才继续校验。
                String apiKey = values.nextElement(); // 取出唯一候选值，仅在当前请求内使用。
                var current = SecurityContextHolder.getContext().getAuthentication(); // 检查当前请求是否已有认证身份。
                if (!values.hasMoreElements() // 重复的业务 Key 请求头不能获得身份。
                        && (current == null || current instanceof AnonymousAuthenticationToken) // 不覆盖已有的管理员或测试身份。
                        && apiKeys.isActive(apiKey)) { // 只有数据库中尚未撤销的 Key 才能建立身份。
                    var authentication = new UsernamePasswordAuthenticationToken( // 创建已认证的 Spring Security 身份。
                            "business-api-key", // 使用固定标识，不保存或传播 Key 明文。
                            null, // credentials 留空，避免凭据进入安全上下文。
                            List.of(new SimpleGrantedAuthority(BUSINESS_ROLE)) // 只授予业务角色，不授予管理员角色。
                    ); // 业务认证对象创建结束。
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request)); // 附加标准来源信息，不附加请求头。
                    SecurityContextHolder.getContext().setAuthentication(authentication); // 保存本次请求的业务身份。
                } // 有效且无歧义的业务 Key 校验结束。
            } // 业务请求头读取结束。
        } // 两种凭据分离检查结束。
        filterChain.doFilter(request, response); // 交给后续授权规则决定 200、401 或 403。
    } // 单次请求处理结束。
} // 业务 Key 过滤器结束。