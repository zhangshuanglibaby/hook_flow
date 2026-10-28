package com.hookflow.common.api; // 将通用异常映射放在共享 API 层。

import java.util.UUID; // 过滤器未提供 ID 时使用兜底值。
import jakarta.validation.ConstraintViolationException; // 识别 Bean Validation 约束异常。
import org.slf4j.Logger; // 输出不含请求内容的简要错误日志。
import org.slf4j.LoggerFactory; // 创建当前类的日志器。
import org.springframework.security.authentication.BadCredentialsException; // 识别登录时用户名或密码错误。
import org.springframework.http.HttpHeaders; // 保留框架为错误响应设置的必要响应头。
import org.springframework.http.HttpStatus; // 使用标准 HTTP 状态。
import org.springframework.http.HttpStatusCode; // 接收框架确定的错误状态。
import org.springframework.http.ResponseEntity; // 同时返回状态码和错误体。
import org.springframework.web.bind.annotation.ExceptionHandler; // 声明额外处理的异常。
import org.springframework.web.bind.annotation.RestControllerAdvice; // 将异常处理应用于 MVC 控制器。
import org.springframework.web.context.request.RequestAttributes; // 指定从当前请求范围读取 ID。
import org.springframework.web.context.request.WebRequest; // 读取框架传入的请求属性。
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler; // 接管常见 MVC 参数与 JSON 解析异常。
import com.hookflow.config.RequestIdFilter; // 使用过滤器保存请求 ID 的属性名。
import com.hookflow.endpoint.application.EndpointTaskSnapshotService.EndpointDisabledException; // 识别事务内发现的接收端停用异常。

@RestControllerAdvice // 将此类注册为返回 JSON 的全局 MVC 异常处理器。
public class ApiExceptionHandler extends ResponseEntityExceptionHandler { // 复用 Spring MVC 对常见请求异常的分类。
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class); // 集中输出安全的异常类别日志。

    @Override // 改写框架异常最终形成响应体的步骤。
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) { // 处理参数、JSON、请求方法等 MVC 异常。
        if (status.is5xxServerError()) { // 服务端异常需要较高日志级别。
            log.error("requestFailed exceptionType={}", exception.getClass().getSimpleName()); // 只记异常类型，不记可能含凭据的异常原文。
        } else { // 客户端请求错误使用普通信息级别。
            log.info("requestRejected exceptionType={}", exception.getClass().getSimpleName()); // 不记录请求参数、请求体或异常原文。
        } // 日志级别判断结束。
        ApiError responseBody = error(status, request); // 先生成带本次 requestId 的统一错误体。
        if (exception instanceof TargetNotAllowedException) { // 只识别外部目标安全异常，不改动其他 422 错误。
            responseBody = new ApiError("TARGET_NOT_ALLOWED", "外部目标地址不被允许", responseBody.requestId()); // 使用契约代码并保留原请求 ID。
        } // 目标安全异常映射结束。
        return super.handleExceptionInternal(exception, responseBody, headers, status, request); // 保留框架状态和响应头，返回安全错误体。
    } // 框架异常处理结束。

    @ExceptionHandler(ConstraintViolationException.class) // 处理不属于上述 MVC 内置分类的约束异常。
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception, WebRequest request) { // 将输入约束错误映射为 400。
        log.info("requestRejected exceptionType={}", exception.getClass().getSimpleName()); // 只记录异常类型。
        return ResponseEntity.badRequest().body(error(HttpStatus.BAD_REQUEST, request)); // 返回统一的参数错误体。
    } // 约束异常处理结束。

    @ExceptionHandler(BadCredentialsException.class) // 单独处理登录凭据错误，避免被通用 500 处理器接走。
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception, WebRequest request) { // 返回契约要求的 401 错误。
        log.info("requestRejected exceptionType={}", exception.getClass().getSimpleName()); // 只记录异常类型，不记录用户名或密码。
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(HttpStatus.UNAUTHORIZED, request)); // 使用统一错误体和本次请求 ID。
    } // 登录失败处理结束。

    @ExceptionHandler(EndpointDisabledException.class) // 单独处理接收端停用，不让通用处理器误判为 500。
    public ResponseEntity<ApiError> handleEndpointDisabled(EndpointDisabledException exception,
            WebRequest request) { // 使用当前请求构造契约规定的 422 响应。
        log.info("requestRejected exceptionType={}", exception.getClass().getSimpleName()); // 只记录异常类型，不输出请求内容。
        ApiError base = error(HttpStatus.UNPROCESSABLE_ENTITY, request); // 取得当前请求的 requestId。
        ApiError body = new ApiError("ENDPOINT_DISABLED", "接收端已停用，请先启用",
                base.requestId()); // 使用约定的错误码和固定文案，保留 requestId。
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body); // 返回 HTTP 422 和统一错误体。
    } // 接收端停用处理结束。

    @ExceptionHandler(Exception.class) // 兜住未分类的控制器异常。
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, WebRequest request) { // 将未知服务端错误映射为 500。
        log.error("requestFailed exceptionType={}", exception.getClass().getSimpleName()); // 不输出异常消息或堆栈，避免泄露请求数据。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(HttpStatus.INTERNAL_SERVER_ERROR, request)); // 返回固定的 500 文案。
    } // 未知异常处理结束。

    private ApiError error(HttpStatusCode status, WebRequest request) { // 按状态创建契约规定的三个字段。
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST); // 获取过滤器写入的请求 ID。
        String requestId = attribute instanceof String id ? id : UUID.randomUUID().toString(); // 极端情况下生成兜底 ID，避免返回空值。
        return switch (status.value()) { // 使用稳定代码和固定中文文案，不回显异常原文。
            case 400 -> new ApiError("INVALID_REQUEST", "请求参数不合法", requestId); // 请求字段、类型或 JSON 不合法。
            case 401 -> new ApiError("UNAUTHORIZED", "用户名或密码错误", requestId); // 登录失败不区分账号不存在与密码错误。
            case 404 -> new ApiError("NOT_FOUND", "请求的资源不存在", requestId); // 请求的资源不存在。
            case 405 -> new ApiError("METHOD_NOT_ALLOWED", "请求方法不被允许", requestId); // HTTP 方法不适用。
            case 415 -> new ApiError("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不被支持", requestId); // 请求的媒体类型不适用。
            default -> status.is5xxServerError() // 其他服务端错误同样不暴露内部细节。
                    ? new ApiError("INTERNAL_ERROR", "服务暂时无法处理请求", requestId) // 固定的 5xx 响应。
                    : new ApiError("INVALID_REQUEST", "请求无法处理", requestId); // 其他尚未细分的请求错误。
        }; // 状态映射结束。
    } // 错误体创建方法结束。
} // 全局异常处理器结束。