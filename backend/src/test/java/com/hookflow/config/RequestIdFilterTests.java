package com.hookflow.config; // 与被测试的过滤器使用同一个包。

import static org.junit.jupiter.api.Assertions.assertEquals; // 检查两个位置的 ID 相同。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 检查未采用客户端指定的 ID。
import static org.junit.jupiter.api.Assertions.assertNotNull; // 检查已生成 ID。
import static org.junit.jupiter.api.Assertions.assertNull; // 检查处理结束后已清理 MDC。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.slf4j.MDC; // 读取请求处理期间的日志上下文。
import org.springframework.mock.web.MockHttpServletRequest; // 创建不需启动服务器的模拟请求。
import org.springframework.mock.web.MockHttpServletResponse; // 创建不需启动服务器的模拟响应。

class RequestIdFilterTests { // 定义过滤器的单元测试。
    @Test // 让 JUnit 执行下面的测试。
    void associatesRequestAndClearsMdc() throws Exception { // 验证关联 ID 的写入与清理。
        MockHttpServletRequest request = new MockHttpServletRequest(); // 准备模拟请求。
        request.addHeader("X-Request-Id", "client-controlled"); // 模拟客户端提供一个不可信的 ID。
        MockHttpServletResponse response = new MockHttpServletResponse(); // 准备模拟响应。
        RequestIdFilter filter = new RequestIdFilter(); // 创建本次要测试的过滤器。

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { // 模拟过滤器后续的请求处理。
            String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); // 读取供错误处理器使用的 ID。
            assertNotNull(requestId); // 请求处理期间必须已经生成 ID。
            assertNotEquals("client-controlled", requestId); // 服务端不能直接采用客户端指定的 ID。
            assertEquals(requestId, response.getHeader("X-Request-Id")); // 响应头必须是同一个 ID。
            assertEquals(requestId, MDC.get("requestId")); // 请求处理期间的日志上下文必须是同一个 ID。
        }); // 模拟的后续处理结束。

        assertNull(MDC.get("requestId")); // 过滤器返回后不得留下本次请求的日志上下文。
    } // 测试方法结束。
} // 测试类结束。