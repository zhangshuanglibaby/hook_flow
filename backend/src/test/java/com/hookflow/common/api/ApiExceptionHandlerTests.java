package com.hookflow.common.api; // 测试通用 API 异常处理器。

import static org.junit.jupiter.api.Assertions.assertFalse; // 断言敏感标记未出现。
import static org.junit.jupiter.api.Assertions.assertNotNull; // 断言响应头包含请求 ID。
import static org.junit.jupiter.api.Assertions.assertTrue; // 断言日志可关联请求。
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post; // 构造模拟 POST 请求。
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath; // 检查 JSON 字段。
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status; // 检查 HTTP 状态。
import ch.qos.logback.classic.Logger; // 获取测试期间的日志器。
import ch.qos.logback.classic.spi.ILoggingEvent; // 读取捕获的日志事件。
import ch.qos.logback.core.read.ListAppender; // 暂存测试期间产生的日志。
import com.hookflow.config.RequestIdFilter; // 将真实的请求 ID 过滤器加入测试链。
import jakarta.validation.Valid; // 触发请求对象校验。
import jakarta.validation.constraints.NotBlank; // 定义测试用的非空约束。
import org.junit.jupiter.api.AfterEach; // 每次测试后释放校验器。
import org.junit.jupiter.api.BeforeEach; // 每次测试前建立独立的 MVC 环境。
import org.junit.jupiter.api.Test; // 标记测试方法。
import org.slf4j.LoggerFactory; // 获取异常处理器的日志器。
import org.springframework.http.MediaType; // 指定请求为 JSON。
import org.springframework.test.web.servlet.MockMvc; // 模拟 MVC 请求处理。
import org.springframework.test.web.servlet.MvcResult; // 读取响应头和响应内容。
import org.springframework.test.web.servlet.setup.MockMvcBuilders; // 创建独立的 MVC 测试环境。
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean; // 使用真实的 Bean Validation 校验器。
import org.springframework.web.bind.annotation.PostMapping; // 声明仅供测试使用的路由。
import org.springframework.web.bind.annotation.RequestBody; // 从 JSON 读取测试参数。
import org.springframework.web.bind.annotation.RestController; // 注册测试专用控制器。

class ApiExceptionHandlerTests { // 验证过滤器、参数校验和异常映射的协作。
    private static final String MARKER = "probe-credential-do-not-log"; // 虚构标记，不是真实凭据。
    private LocalValidatorFactoryBean validator; // 保存本次测试使用的校验器。
    private MockMvc mvc; // 保存本次测试使用的请求模拟器。

    @BeforeEach // 每个测试开始前重新创建环境。
    void setUp() { // 装配测试专用控制器和正式的公共设施。
        validator = new LocalValidatorFactoryBean(); // 创建 Bean Validation 适配器。
        validator.afterPropertiesSet(); // 初始化实际的约束校验实现。
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController()) // 仅注册测试夹具，不增加正式接口。
                .setControllerAdvice(new ApiExceptionHandler()) // 使用正式的统一异常处理器。
                .setValidator(validator) // 让 @Valid 约束在请求中实际生效。
                .addFilters(new RequestIdFilter()) // 使用正式的请求 ID 过滤器。
                .build(); // 完成模拟 MVC 环境。
    } // 测试环境准备结束。

    @AfterEach // 每个测试结束后释放资源。
    void tearDown() { // 关闭校验器持有的资源。
        validator.close(); // 避免测试间共享校验器状态。
    } // 资源清理结束。

    @Test // 验证参数校验失败时的统一响应。
    void invalidInputReturns400WithRequestId() throws Exception { // 空白字段应被 @NotBlank 拒绝。
        MvcResult result = mvc.perform(post("/__test__/error-probe") // 调用测试专用路由。
                        .contentType(MediaType.APPLICATION_JSON) // 按 JSON 提交参数。
                        .content("{\"value\":\"\"}")) // 使用无效字段触发校验失败。
                .andExpect(status().isBadRequest()) // 校验失败必须返回 400。
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")) // 检查稳定错误代码。
                .andExpect(jsonPath("$.message").value("请求参数不合法")) // 检查固定、安全的中文文案。
                .andReturn(); // 保留响应，以便检查动态生成的 ID。
        String requestId = result.getResponse().getHeader("X-Request-Id"); // 读取服务端生成的关联 ID。
        assertNotNull(requestId); // 错误响应必须携带关联头。
        jsonPath("$.requestId").value(requestId).match(result); // 错误体中的 ID 必须与响应头相同。
    } // 400 响应验证结束。

    @Test // 验证未知错误的脱敏响应及日志关联。
    void unexpectedFailureReturns500AndCorrelatedSafeLog() throws Exception { // 有效参数进入测试控制器后触发异常。
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class); // 获取异常处理器的日志器。
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); // 只捕获本次测试关注的日志。
        appender.start(); // 启动内存日志收集器。
        logger.addAppender(appender); // 临时挂到异常处理器上。
        try { // 确保断言失败时也会卸载日志收集器。
            MvcResult result = mvc.perform(post("/__test__/error-probe") // 调用同一测试路由。
                            .contentType(MediaType.APPLICATION_JSON) // 按 JSON 提交参数。
                            .content("{\"value\":\"ok\"}")) // 有效参数会进入抛错分支。
                    .andExpect(status().isInternalServerError()) // 未知异常必须返回 500。
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")) // 检查稳定错误代码。
                    .andExpect(jsonPath("$.message").value("服务暂时无法处理请求")) // 不向客户端透露异常详情。
                    .andReturn(); // 保留动态生成的响应内容。
            String requestId = result.getResponse().getHeader("X-Request-Id"); // 读取本次请求 ID。
            assertNotNull(requestId); // 确认响应头存在。
            jsonPath("$.requestId").value(requestId).match(result); // 确认错误体与响应头使用同一 ID。
            assertFalse(result.getResponse().getContentAsString().contains(MARKER)); // 响应不得泄露虚构敏感标记。
            assertTrue(appender.list.stream().anyMatch(event -> // 至少有一条处理失败的日志。
                    requestId.equals(event.getMDCPropertyMap().get("requestId")) // 日志 MDC 必须关联本次请求。
                            && "requestFailed exceptionType=IllegalStateException".equals(event.getFormattedMessage()))); // 日志只写异常类别。
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(MARKER))); // 日志正文不得泄露标记。
        } finally { // 无论结果如何都恢复日志器。
            logger.detachAppender(appender); // 移除本次测试的内存收集器。
            appender.stop(); // 关闭收集器。
        } // 日志清理结束。
    } // 500 响应和日志验证结束。

    public record ProbeInput(@NotBlank String value) { // 仅用于验证参数校验的测试输入。
    } // 测试输入定义结束。

    @RestController // 仅在这个测试中注册，不属于生产应用。
    public static class ProbeController { // 用同一路由分别触发校验失败和未知异常。
        @PostMapping("/__test__/error-probe") // 路径只存在于独立测试环境。
        public void probe(@Valid @RequestBody ProbeInput input) { // 先由 MVC 校验输入。
            throw new IllegalStateException(MARKER); // 校验通过后模拟带虚构敏感内容的内部异常。
        } // 测试动作结束。
    } // 测试专用控制器结束。
} // 测试类结束。