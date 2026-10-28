package com.hookflow.endpoint.api; // 管理员接收端接口属于接收端 API 层。

import java.security.Principal; // 读取已认证管理员的内部 ID。
import java.util.List; // 表示分页响应中的接收端条目。
import java.util.Map; // 按字段名检查 PATCH 的 JSON 对象。
import jakarta.servlet.http.HttpServletRequest; // 读取服务端保存的请求 ID。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.http.HttpStatus; // 指定参数错误及资源不存在的状态码。
import org.springframework.http.MediaType; // 声明 JSON 请求与响应类型。
import org.springframework.web.bind.annotation.GetMapping; // 注册 GET 接口。
import org.springframework.web.bind.annotation.PatchMapping; // 注册 PATCH 接口。
import org.springframework.web.bind.annotation.PathVariable; // 读取路径中的接收端 ID。
import org.springframework.web.bind.annotation.RequestBody; // 读取 PATCH 的 JSON 请求体。
import org.springframework.web.bind.annotation.RequestParam; // 读取分页参数。
import org.springframework.web.bind.annotation.RestController; // 注册 JSON 控制器。
import org.springframework.web.server.ResponseStatusException; // 交给统一异常处理器生成错误响应。
import com.hookflow.config.RequestIdFilter; // 使用服务端请求 ID 的属性名称。
import com.hookflow.endpoint.application.EndpointQueryService; // 调用接收端查询服务。
import com.hookflow.endpoint.application.EndpointUpdateService; // 调用接收端编辑事务服务。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 将内部配置映射为安全响应。

@Profile("!skeleton") // 无数据库配置不注册接收端接口。
@RestController // 交由 Spring MVC 处理管理员 HTTP 请求。
public class EndpointController { // 提供列表、详情和当前安全范围内的编辑接口。
    private final EndpointQueryService queries; // 保存查询应用服务。
    private final EndpointUpdateService updates; // 保存编辑应用服务。

    public EndpointController(EndpointQueryService queries, EndpointUpdateService updates) { // 由 Spring 注入两个应用服务。
        this.queries = queries; // 保存服务供 GET 接口使用。
        this.updates = updates; // 保存服务供 PATCH 接口使用。
    } // 构造方法结束。

    @GetMapping(path = "/api/v1/admin/endpoints", produces = MediaType.APPLICATION_JSON_VALUE) // 注册管理员接收端列表。
    public EndpointPageView list(@RequestParam(required = false) String page, @RequestParam(required = false) String size) { // 接收可选页码和条数。
        int pageNumber = page == null ? 1 : positiveInt(page); // 缺省为第 1 页，提供时必须是正整数。
        int pageSize = size == null ? 20 : positiveInt(size); // 缺省每页 20 条。
        if (pageSize > 100) { // 执行接口契约规定的每页上限。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 超出上限返回统一 400。
        } // 每页上限检查结束。
        EndpointQueryService.EndpointPage result = queries.list(pageNumber, pageSize); // 查询总数及本页数据。
        List<EndpointView> items = result.items().stream().map(EndpointController::toView).toList(); // 仅映射可公开字段。
        return new EndpointPageView(result.page(), result.size(), result.total(), items); // 返回统一分页结构。
    } // 列表接口结束。

    @GetMapping(path = "/api/v1/admin/endpoints/{id}", produces = MediaType.APPLICATION_JSON_VALUE) // 注册管理员接收端详情。
    public EndpointView detail(@PathVariable String id) { // 将路径 ID 先当字符串校验。
        long endpointId = positiveLong(id); // 拒绝非正整数及超出 long 范围的 ID。
        EndpointConfiguration configuration = queries.findById(endpointId) // 按内部 ID 查找接收端。
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // 不存在时返回统一 404。
        return toView(configuration); // 只返回不含签名密钥的详情。
    } // 详情接口结束。

    @PatchMapping(path = "/api/v1/admin/endpoints/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 注册管理员接收端编辑接口。
    public EndpointView update(@PathVariable String id, @RequestBody Map<String, Object> body, Principal principal, HttpServletRequest request) { // 接收路径 ID、JSON、管理员身份和请求信息。
        long endpointId = positiveLong(id); // 在访问数据库前校验路径 ID。
        if (body == null || body.isEmpty() || body.keySet().stream().anyMatch(key -> !key.equals("name") && !key.equals("url") && !key.equals("timeoutMs"))) { // 只接受至少一个允许编辑的字段。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝类型、启停、密钥及其他未知字段。
        } // 可编辑字段检查结束。
        String name = body.containsKey("name") ? EndpointInputValidation.name(body.get("name")) : null; // 出现名称时校验；缺省表示保留原值。
        String url = body.containsKey("url") ? ExternalUrlValidation.validate(body.get("url")) : null; // 出现 URL 时先校验；显式 null 会被拒绝。
        Integer timeoutMs = body.containsKey("timeoutMs") ? EndpointInputValidation.timeoutMs(body.get("timeoutMs")) : null; // 出现超时时校验整数范围。
        long actorUserId = Long.parseLong(principal.getName()); // 管理员过滤器提供数据库用户 ID。
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); // 只使用服务端生成的请求 ID。
        EndpointConfiguration configuration = updates.update(endpointId, name, url, timeoutMs, actorUserId, requestId) // 在事务内复查 URL、更新配置并写成功审计。
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // 接收端不存在时返回 404。
        return toView(configuration); // 响应仍不包含签名密钥。
    } // 接收端编辑接口结束。

    private static int positiveInt(String value) { // 校验分页参数为十进制正整数。
        if (!value.matches("[1-9][0-9]*")) { // 拒绝空值、负数、小数和其他字符。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 参数格式不合法。
        } // 格式检查结束。
        try { // 继续检查数字是否位于 Java int 范围内。
            return Integer.parseInt(value); // 返回可用的正整数。
        } catch (NumberFormatException exception) { // 数字过大时由解析器抛出异常。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 不回显用户输入。
        } // 整数范围检查结束。
    } // 分页整数解析结束。

    private static long positiveLong(String value) { // 校验接收端路径 ID 为十进制正整数。
        if (!value.matches("[1-9][0-9]*")) { // 拒绝零、负数、小数和非数字。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 路径参数不合法。
        } // 格式检查结束。
        try { // 继续检查数字是否位于 Java long 范围内。
            return Long.parseLong(value); // 返回有效的数据库内部 ID。
        } catch (NumberFormatException exception) { // 超出 long 范围时拒绝。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 使用统一 400，不回显输入。
        } // ID 范围检查结束。
    } // 路径 ID 解析结束。

    private static EndpointView toView(EndpointConfiguration item) { // 将内部配置转换为对外安全结构。
        return new EndpointView( // 严格按安全响应 record 的字段顺序构造。
                item.id(), item.name(), item.targetType(), item.url(), // 返回标识及非敏感目标配置。
                item.timeoutMs(), item.enabled(), item.createdAt(), item.updatedAt() // 返回超时、状态和 UTC 时间。
        ); // 此对象没有签名密钥或密文字段。
    } // 安全响应转换结束。

    public record EndpointPageView(int page, int size, long total, List<EndpointView> items) { // 对应契约中的 page、size、total、items。
    } // 分页响应结构结束。
} // 接收端控制器结束。