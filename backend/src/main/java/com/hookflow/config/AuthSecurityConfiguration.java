package com.hookflow.config; // 将 HTTP 安全边界放在共用配置层。

import org.springframework.context.annotation.Bean; // 注册安全过滤链。
import org.springframework.context.annotation.Configuration; // 声明 Spring 配置类。
import org.springframework.http.HttpMethod; // 精确限定公开接口的 HTTP 方法。
import org.springframework.security.config.annotation.web.builders.HttpSecurity; // 配置 HTTP 安全规则。
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer; // 关闭不使用的默认登录方式。
import org.springframework.security.config.http.SessionCreationPolicy; // 不创建服务端 HTTP 登录会话。
import org.springframework.security.web.SecurityFilterChain; // 返回实际执行的安全过滤链。

@Configuration // skeleton 与正式环境都使用相同的基本边界。
public class AuthSecurityConfiguration { // 只配置 P2-01 当前需要的公开路径。
    @Bean // 让 Spring Security 使用这条过滤链。
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception { // 声明公开路径及其他请求的默认行为。
        http.csrf(csrf -> csrf.disable()); // 此 API 使用无浏览器会话的 JSON 请求，不依赖 CSRF Token。
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)); // 不用 HTTP Session 保存登录状态。
        http.formLogin(AbstractHttpConfigurer::disable); // 不暴露默认 HTML 登录表单。
        http.httpBasic(AbstractHttpConfigurer::disable); // 不接受默认 Basic 用户名密码登录。
        http.logout(AbstractHttpConfigurer::disable); // 不启用框架默认登出路径，项目登出属于后续任务。
        http.authorizeHttpRequests(authorize -> authorize // 逐条限定目前允许访问的路径。
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll() // 允许不带 Token 提交登录请求。
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll() // 保持最小健康检查公开。
                .anyRequest().denyAll()); // 其他路径暂不开放，后续任务再添加各自的身份校验。
        return http.build(); // 构造供应用实际执行的过滤链。
    } // 安全过滤链配置结束。
} // 安全配置类结束。