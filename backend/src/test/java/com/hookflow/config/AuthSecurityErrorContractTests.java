package com.hookflow.config; // 测试安全配置层返回的 403 错误契约。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较 HTTP 状态和 JSON 字段。
import static org.junit.jupiter.api.Assertions.assertFalse; // 确认错误响应中的 requestId 不为空。

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get; // 构造模拟 GET 请求。
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status; // 检查 HTTP 状态码。

import java.util.Map; // 为测试专用 Controller 构造简单 JSON 响应。

import org.junit.jupiter.api.Test; // 标记测试方法。
import org.springframework.beans.factory.annotation.Autowired; // 注入 MockMvc 和 JSON 映射器。
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; // 启动轻量 MVC 测试环境。
import org.springframework.context.annotation.Import; // 导入正式安全配置和 requestId 过滤器。
import org.springframework.security.test.context.support.WithMockUser; // 在测试请求中建立测试身份。
import org.springframework.test.web.servlet.MockMvc; // 发送模拟 HTTP 请求。
import org.springframework.test.web.servlet.MvcResult; // 读取模拟 HTTP 响应。
import org.springframework.web.bind.annotation.GetMapping; // 声明测试专用 GET 路由。
import org.springframework.web.bind.annotation.RestController; // 声明测试专用 Controller。
import org.springframework.test.context.ActiveProfiles; // 让该 MVC 测试使用不连接数据库的 skeleton 配置。

import tools.jackson.databind.JsonNode; // 结构化读取 JSON 错误体。
import tools.jackson.databind.json.JsonMapper; // 使用应用实际使用的 JSON 映射器。

@WebMvcTest(controllers = AuthSecurityErrorContractTests.SecurityProbeController.class) // 只启动本测试需要的 MVC Controller 和安全测试设施。
@ActiveProfiles("skeleton") // 使用不连接数据库的测试配置，避免要求真实环境变量。
@Import({AuthSecurityConfiguration.class, RequestIdFilter.class}) // 导入正式安全过滤链和请求关联 ID 过滤器。
class AuthSecurityErrorContractTests { // 验证已认证但无管理员权限时的 403 响应。

    @Autowired // 从测试应用上下文注入模拟 HTTP 客户端。
    private MockMvc mvc; // 保存本测试使用的 MockMvc。

    @Autowired // 从测试应用上下文注入统一 JSON 映射器。
    private JsonMapper mapper; // 保存用于读取错误响应的 JSON 映射器。

    @Test // 执行 403 错误契约验证。
    @WithMockUser(username = "test-user", roles = "USER") // 建立已认证但只有 USER 角色的测试身份。
    void authenticatedNonAdminReturns403WithRequestId() throws Exception { // 验证非管理员不能访问管理员接口。
        MvcResult result = mvc.perform(get("/api/v1/admin/test-forbidden")) // 请求测试专用的管理员路径。
                .andExpect(status().isForbidden()) // 已认证但缺少 ADMIN 角色必须返回 HTTP 403。
                .andReturn(); // 保留完整响应以便检查 JSON 和响应头。

        JsonNode error = mapper.readTree(result.getResponse().getContentAsString()); // 结构化读取安全层返回的 JSON 错误体。
        String responseRequestId = result.getResponse().getHeader("X-Request-Id"); // 读取响应头中的 requestId。

        assertEquals("FORBIDDEN", error.path("code").asString()); // 错误代码必须符合 P0-02 契约。
        assertFalse(responseRequestId == null || responseRequestId.isBlank()); // 响应头中的 requestId 必须存在且非空。
        assertEquals(responseRequestId, error.path("requestId").asString()); // 错误体和响应头必须使用同一个 requestId。
    } // 403 错误契约测试结束。

    @RestController // 仅在本测试 MVC 环境中注册，不属于正式应用接口。
    static class SecurityProbeController { // 定义仅用于触发安全授权判断的测试 Controller。

        @GetMapping("/api/v1/admin/test-forbidden") // 使用管理员路径触发 ROLE_ADMIN 授权规则。
        Map<String, Boolean> probe() { // 如果错误地放行请求，则返回测试标记。
            return Map.of("ok", true); // 正常情况下此方法不应被 ROLE_USER 请求执行。
        } // 测试 Controller 方法结束。
    } // 测试专用 Controller 定义结束。
} // 403 安全错误契约测试结束。