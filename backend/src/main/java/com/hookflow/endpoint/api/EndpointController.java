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
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointEnableService;
import com.hookflow.endpoint.application.EndpointQueryService; // 调用接收端查询服务。
import com.hookflow.endpoint.application.EndpointUpdateService; // 调用接收端编辑事务服务。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 将内部配置映射为安全响应。
import org.springframework.http.HttpHeaders; // 设置一次性创建响应不被缓存。
import org.springframework.http.ResponseEntity; // 同时指定创建状态、响应头和响应体。
import org.springframework.web.bind.annotation.PostMapping; // 注册管理员创建接口。
import com.hookflow.endpoint.application.EndpointSecretProvisioningService; // 调用加密入库及审计的创建服务。
import com.hookflow.endpoint.domain.EndpointTargetType; // 区分 EXTERNAL 与 SIMULATOR。

@Profile("!skeleton") // 无数据库配置不注册接收端接口。
@RestController // 交由 Spring MVC 处理管理员 HTTP 请求。
public class EndpointController { // 提供列表、详情和当前安全范围内的编辑接口。
    private final EndpointEnableService enabling;
    private final EndpointDisableService disabling;
    private final EndpointQueryService queries; // 保存查询应用服务。
    private final EndpointUpdateService updates; // 保存编辑应用服务。
    private final EndpointSecretProvisioningService provisioning; // 保存创建、密钥加密和审计服务。

    public EndpointController(EndpointQueryService queries, EndpointUpdateService updates,
            EndpointSecretProvisioningService provisioning,
            EndpointDisableService disabling, EndpointEnableService enabling) {
        this.enabling = enabling;
        this.disabling = disabling;
        this.queries = queries; // 保存服务供 GET 接口使用。
        this.updates = updates; // 保存服务供 PATCH 接口使用。
        this.provisioning = provisioning; // 保存服务供 POST 接口使用。
    } // 构造方法结束。


    @PostMapping(path = "/api/v1/admin/endpoints", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE) // 注册管理员创建接口。
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Principal principal, HttpServletRequest request) { // 接收 JSON、管理员身份和服务端请求 ID。
        if (body == null || body.keySet().stream().anyMatch(key -> // 请求必须是对象，且只能含契约允许的字段。
                !key.equals("name") && !key.equals("targetType") && !key.equals("url")
                        && !key.equals("timeoutMs") && !key.equals("enabled"))) { // 未知字段一律拒绝。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 避免拼错字段后静默创建。
        } // 请求字段集合检查结束。
        String name = EndpointInputValidation.name(body.get("name")); // 必填名称缺失、空白或类型错误均返回 400。
        EndpointTargetType targetType = EndpointInputValidation.targetType(body.get("targetType")); // 只接受精确的两种目标类型。
        int timeoutMs = body.containsKey("timeoutMs") // 可选超时由接口契约提供默认值。
                ? EndpointInputValidation.timeoutMs(body.get("timeoutMs")) // 显式提供时严格检查整数和范围。
                : 5000; // 未提供时默认 5000 毫秒。
        boolean enabled = true; // 未提供启用状态时默认启用。
        if (body.containsKey("enabled")) { // 显式提供时不能把字符串或 null 当作布尔值。
            if (!(body.get("enabled") instanceof Boolean requestedEnabled)) { // 只接受 JSON true 或 false。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 错误类型不进入创建事务。
            } // 启用状态类型检查结束。
            enabled = requestedEnabled; // 保存管理员明确指定的布尔值。
        } // 可选启用状态处理结束。
        String url = null; // SIMULATOR 没有用户 URL。
        if (targetType == EndpointTargetType.EXTERNAL) { // EXTERNAL 必须提供可保存的公开 HTTPS 地址。
            url = ExternalUrlValidation.validate(body.get("url")); // 格式错误返回 400，禁止目标返回 422。
        } else if (body.containsKey("url")) { // SIMULATOR 即使传入 null 也属于提供了 URL 字段。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 禁止借模拟器创建绕过外部 URL 安全策略。
        } // 目标类型与 URL 的对应关系检查结束。
        long actorUserId = Long.parseLong(principal.getName()); // 使用安全过滤器认证的管理员数据库 ID。
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); // 只使用服务端生成的请求 ID。
        if (targetType == EndpointTargetType.EXTERNAL) { // 外部接收端成功创建才交付一次性明文密钥。
            EndpointSecretProvisioningService.CreatedExternalEndpoint created = provisioning.createExternal( // 开启加密入库及同事务审计。
                    name, url, timeoutMs, enabled, actorUserId, requestId // 传入已校验的请求字段和可信身份。
            ); // 事务成功提交后才取得创建结果。
            return ResponseEntity.status(HttpStatus.CREATED) // 成功新建返回 201。
                    .header(HttpHeaders.CACHE_CONTROL, "no-store") // 禁止缓存含一次性密钥的响应。
                    .body(ExternalEndpointCreationView.of(toView(created.configuration()), created.signingSecret())); // 只在此次响应附加明文密钥。
        } // EXTERNAL 响应结束。
        EndpointConfiguration created = provisioning.createSimulator(name, timeoutMs, enabled, actorUserId, requestId); // 固定内部目标并同事务审计。
        return ResponseEntity.status(HttpStatus.CREATED) // 模拟器新建同样返回 201。
                .header(HttpHeaders.CACHE_CONTROL, "no-store") // 创建响应不由缓存复用。
                .body(toView(created)); // 模拟器响应只含普通安全字段，没有密钥。
    } // 管理员创建接口结束。

    @GetMapping(path = "/api/v1/admin/endpoints", produces = MediaType.APPLICATION_JSON_VALUE) // 注册管理员接收端列表。
    public EndpointPageView list(@RequestParam(required = false) String page, @RequestParam(required = false) String size) { // 接收可选页码和条数。
        var pagination = com.hookflow.common.api.PageParameters.parse(page, size);
        int pageNumber = pagination.page();
        int pageSize = pagination.size();
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

    @PostMapping(path = "/api/v1/admin/endpoints/{id}/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    public EndpointView enable(@PathVariable String id, @RequestBody(required = false) byte[] body,
            Principal principal, HttpServletRequest request) {
        long endpointId = positiveLong(id);
        if (body != null && body.length != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return toView(enabling.enable(endpointId, Long.parseLong(principal.getName()),
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping(path = "/api/v1/admin/endpoints/{id}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    public EndpointView disable(@PathVariable String id, @RequestBody(required = false) byte[] body,
            Principal principal, HttpServletRequest request) {
        long endpointId = positiveLong(id);
        if (body != null && body.length != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return toView(disabling.disable(endpointId, Long.parseLong(principal.getName()),
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

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