package com.hookflow.endpoint.api; // 测试接收端 API 层的字段校验。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较合法输入的结果和错误状态。
import static org.junit.jupiter.api.Assertions.assertThrows; // 确认非法输入被拒绝。
import org.junit.jupiter.api.Test; // 标记单元测试。
import org.springframework.web.server.ResponseStatusException; // 检查校验失败返回的 HTTP 状态。
import com.hookflow.endpoint.domain.EndpointTargetType; // 比较合法的目标类型。

class EndpointInputValidationTests { // 不启动应用或数据库的纯字段校验测试。
    @Test // 验证名称长度按 Unicode 码点计算。
    void validatesName() { // 覆盖合法边界和非法输入。
        assertEquals("接收端", EndpointInputValidation.name("接收端")); // 普通中文名称可以使用。
        assertEquals("😀".repeat(100), EndpointInputValidation.name("😀".repeat(100))); // 100 个表情符号是 100 个码点。
        assertBadRequest(() -> EndpointInputValidation.name("😀".repeat(101))); // 超过 100 个码点必须拒绝。
        assertBadRequest(() -> EndpointInputValidation.name("   ")); // 全空白名称必须拒绝。
        assertBadRequest(() -> EndpointInputValidation.name(123)); // JSON 数字不能当成名称。
        assertBadRequest(() -> EndpointInputValidation.name(null)); // 缺失或空值不能当成名称。
    } // 名称测试结束。

    @Test // 验证超时的整数类型和上下限。
    void validatesTimeout() { // 覆盖两个边界值及默认值的合法性。
        assertEquals(1, EndpointInputValidation.timeoutMs(1)); // 最小值允许。
        assertEquals(5000, EndpointInputValidation.timeoutMs(5000)); // 契约默认值允许。
        assertEquals(15000, EndpointInputValidation.timeoutMs(15000)); // 最大值允许。
        assertBadRequest(() -> EndpointInputValidation.timeoutMs(0)); // 零不允许。
        assertBadRequest(() -> EndpointInputValidation.timeoutMs(15001)); // 超过上限不允许。
        assertBadRequest(() -> EndpointInputValidation.timeoutMs(1.5)); // JSON 小数不允许。
        assertBadRequest(() -> EndpointInputValidation.timeoutMs("5000")); // JSON 字符串不能冒充整数。
        assertBadRequest(() -> EndpointInputValidation.timeoutMs(null)); // 空值不允许。
    } // 超时测试结束。

    @Test // 验证目标类型只能是两个精确枚举值。
    void validatesTargetType() { // 拒绝大小写变化及未知类型。
        assertEquals(EndpointTargetType.EXTERNAL, EndpointInputValidation.targetType("EXTERNAL")); // 外部类型合法。
        assertEquals(EndpointTargetType.SIMULATOR, EndpointInputValidation.targetType("SIMULATOR")); // 模拟器类型合法。
        assertBadRequest(() -> EndpointInputValidation.targetType("external")); // 不自动转换大小写。
        assertBadRequest(() -> EndpointInputValidation.targetType("OTHER")); // 未约定类型不允许。
        assertBadRequest(() -> EndpointInputValidation.targetType(null)); // 空类型不允许。
    } // 目标类型测试结束。

    private static void assertBadRequest(Runnable action) { // 统一检查非法输入对应的状态。
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run); // 执行校验并取得异常。
        assertEquals(400, error.getStatusCode().value()); // 非法字段必须映射为 HTTP 400。
    } // 错误状态辅助方法结束。
} // 字段校验测试类结束。