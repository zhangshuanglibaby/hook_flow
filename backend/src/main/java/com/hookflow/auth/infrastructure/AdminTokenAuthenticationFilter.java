package com.hookflow.auth.infrastructure; // 管理员 Token 过滤器属于认证模块的基础设施层。

import java.io.IOException; // 过滤器链执行可能抛出输入输出异常。
import java.util.List; // 构造管理员角色权限列表。
import java.util.Optional; // 表示请求中可能不存在合法 Bearer Token。

import jakarta.servlet.FilterChain; // 表示后续安全过滤器和 Controller 的执行链。
import jakarta.servlet.ServletException; // 表示 Servlet 处理过程中的异常。
import jakarta.servlet.http.HttpServletRequest; // 读取当前 HTTP 请求。
import jakarta.servlet.http.HttpServletResponse; // 当前过滤器暂不直接写入响应。

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // 创建已认证的 Spring Security 身份对象。
import org.springframework.security.core.authority.SimpleGrantedAuthority; // 创建管理员角色权限。
import org.springframework.security.core.context.SecurityContextHolder; // 保存当前请求线程的认证身份。
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource; // 为认证对象附加请求来源详情。
import org.springframework.web.filter.OncePerRequestFilter; // 保证一次请求只执行一次过滤逻辑。

import com.hookflow.auth.application.AdminLoginService; // 调用现有服务验证 Token 的数据库哈希和有效期。

import org.springframework.security.authentication.AnonymousAuthenticationToken; // 识别 Spring Security 自动创建的匿名身份。

public final class AdminTokenAuthenticationFilter extends OncePerRequestFilter { // 定义管理员 Bearer Token 请求过滤器。

    private static final String AUTHORIZATION_HEADER = "Authorization"; // 管理员 Token 所使用的 HTTP 请求头名称。

    private static final String BEARER_PREFIX = "Bearer "; // 管理员 Token 必须使用的认证方案前缀。

    private static final String ADMIN_ROLE = "ROLE_ADMIN"; // Spring Security 中统一使用的管理员角色名称。

    private final AdminLoginService loginService; // 保存现有的 Token 校验服务。

    public AdminTokenAuthenticationFilter(AdminLoginService loginService) { // 由安全配置注入登录服务。
        this.loginService = loginService; // 保存登录服务供每个请求校验 Token。
    } // 构造方法结束。

    @Override // 覆盖 OncePerRequestFilter 的单次请求处理方法。
    protected void doFilterInternal( // 开始处理当前 HTTP 请求。
            HttpServletRequest request, // 当前请求对象，用于读取 Authorization 请求头。
            HttpServletResponse response, // 当前响应对象，本过滤器不直接写错误响应。
            FilterChain filterChain // 后续过滤器和 Controller 的执行链。
    ) throws ServletException, IOException { // 允许按照 Servlet 过滤器契约向后抛出异常。

        Optional<String> token = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER)); // 严格解析 Authorization 请求头中的 Bearer Token。

        var currentAuthentication = SecurityContextHolder.getContext().getAuthentication(); // 读取当前安全上下文；未认证请求可能已经被匿名过滤器填充身份。

        if (token.isPresent() // 只有请求带有合法格式的 Bearer Token 时才继续处理。
                && (currentAuthentication == null // 允许当前安全上下文完全没有认证对象的情况。
                        || currentAuthentication instanceof AnonymousAuthenticationToken)) { // 也允许替换 Spring Security 自动创建的匿名身份。
            loginService.validateToken(token.get()).ifPresent(userId -> { // 只有数据库中未撤销且未过期的 Token 才能建立管理员身份。
                var authentication = new UsernamePasswordAuthenticationToken( // 创建已认证的 Spring Security 身份对象。
                        Long.toString(userId), // 将数据库中的管理员内部 ID 作为 principal，避免保存或传播明文 Token。
                        null, // 不把 Token 放入 authentication credentials，降低凭据在内存中扩散的范围。
                        List.of(new SimpleGrantedAuthority(ADMIN_ROLE)) // 为当前请求授予管理员角色。
                ); // 管理员认证对象创建结束。

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request)); // 保存标准请求来源详情，不保存完整请求头或 Token。
                SecurityContextHolder.getContext().setAuthentication(authentication); // 将管理员身份放入当前请求的安全上下文。
            }); // 有效 Token 的身份建立结束。
        } // Token 解析和身份建立结束。

        filterChain.doFilter(request, response); // 将请求交给后续安全授权规则和 Controller。
    } // 单次请求过滤逻辑结束。

    private static Optional<String> extractBearerToken(String authorizationHeader) { // 只解析符合约定格式的 Authorization 请求头。
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) { // 缺失请求头或认证方案不是精确的 Bearer 时拒绝解析。
            return Optional.empty(); // 不把不符合管理员 Token 格式的内容当作身份凭据。
        } // 认证方案检查结束。

        String token = authorizationHeader.substring(BEARER_PREFIX.length()); // 去除 Bearer 前缀，取得待校验的 Token 文本。

        if (token.isBlank() || token.codePoints().anyMatch(Character::isWhitespace)) { // 拒绝空 Token 以及包含空格、制表符或换行等空白字符的 Token。
            return Optional.empty(); // 格式错误由后续安全授权层统一处理为未认证。
        } // Token 文本格式检查结束。

        return Optional.of(token); // 返回待由 AdminLoginService 校验的 Token，不记录也不修改其内容。
    } // Bearer Token 解析方法结束。
} // 管理员 Token 过滤器结束。