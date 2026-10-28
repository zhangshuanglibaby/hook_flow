package com.hookflow.endpoint.api; // 将测试放在接收端 API 包中，便于定位对应的校验器。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较合法 URL 和预期 HTTP 状态。
import static org.junit.jupiter.api.Assertions.assertThrows; // 验证非法 URL 会抛出异常。
import org.junit.jupiter.api.Test; // 标记由 JUnit 执行的测试方法。
import org.springframework.web.server.ResponseStatusException; // 读取校验失败时的 HTTP 状态。

class ExternalUrlValidationTests { // 仅验证保存前的 URL 规则，不模拟真实 HTTP 请求。
    @Test // 验证合法的公开 HTTPS 域名及路径可以保留。
    void acceptsPublicHttpsUrls() { // 成功时应返回原始 URL，供后续保存。
        String url = "https://hooks.acme.com/webhook"; // 使用普通的多标签公开域名。
        assertEquals(url, ExternalUrlValidation.validate(url)); // 校验不得改写合法地址。
        String withPort = "https://hooks.acme.com:443/webhook"; // 显式 HTTPS 端口也合法。
        assertEquals(withPort, ExternalUrlValidation.validate(withPort)); // 保留原始端口与路径。
    } // 合法地址测试结束。

    @Test // 验证语法可解析但不允许保存的目标返回 422。
    void rejectsProhibitedTargets() { // 覆盖协议、IP 字面地址、本地主机和 URL 凭据。
        assertStatus(422, "http://hooks.acme.com/webhook"); // 明文 HTTP 不允许。
        assertStatus(422, "https://127.0.0.1/webhook"); // 回环 IPv4 字面地址不允许。
        assertStatus(422, "https://192.168.1.8/webhook"); // 私网 IPv4 字面地址不允许。
        assertStatus(422, "https://[::1]/webhook"); // 回环 IPv6 字面地址不允许。
        assertStatus(422, "https://localhost/webhook"); // 本地主机名不允许。
        assertStatus(422, "https://receiver.internal/webhook"); // 内部用途后缀不允许。
        assertStatus(422, "https://user:pass@hooks.acme.com/webhook"); // URL 用户信息不允许。
        assertStatus(422, "https://hooks.acme.com/webhook?token=demo"); // 查询串中的凭据不允许。
    } // 禁止目标测试结束。

    @Test // 验证无法作为目标 URL 保存的格式返回 400。
    void rejectsMalformedUrls() { // 覆盖空值、缺主机、非法端口及片段。
        assertStatus(400, null); // 缺失的 URL 不是合法输入。
        assertStatus(400, "  "); // 全空白 URL 不是合法输入。
        assertStatus(400, 123); // 数字不能冒充 URL 字符串。
        assertStatus(400, "https://"); // 缺少主机名。
        assertStatus(400, "https://hooks.acme.com:abc/webhook"); // 端口必须是有效数字。
        assertStatus(400, "https://hooks.acme.com/webhook#part"); // URL 片段不能保存。
    } // 格式错误测试结束。

    private static void assertStatus(int expected, Object raw) { // 复用异常状态的检查逻辑。
        ResponseStatusException error = assertThrows( // 获取校验器抛出的响应状态异常。
                ResponseStatusException.class, // 限定预期异常类型。
                () -> ExternalUrlValidation.validate(raw)); // 用当前输入调用校验器。
        assertEquals(expected, error.getStatusCode().value()); // 确认 400 和 422 没有混淆。
    } // 状态检查辅助方法结束。
} // URL 校验单元测试类结束。