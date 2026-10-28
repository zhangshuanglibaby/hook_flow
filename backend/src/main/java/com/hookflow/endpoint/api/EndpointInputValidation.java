package com.hookflow.endpoint.api; // 接收端请求字段的校验属于 API 层。

import org.springframework.http.HttpStatus; // 指定非法请求返回的状态码。
import org.springframework.web.server.ResponseStatusException; // 使用现有统一错误响应机制。
import com.hookflow.endpoint.domain.EndpointTargetType; // 使用接收端已有的目标类型枚举。

public final class EndpointInputValidation { // 集中校验创建和编辑共用的普通字段。
    private EndpointInputValidation() { // 工具类不需要创建实例。
    } // 私有构造方法结束。

    public static String name(Object raw) { // 校验请求中的接收端名称。
        if (!(raw instanceof String value) || value.isBlank() || value.codePointCount(0, value.length()) > 100) { // 必须是非全空白、最多 100 个 Unicode 码点的字符串。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 不回显非法输入。
        } // 名称校验结束。
        return value; // 保留用户输入的名称，不擅自改变空格。
    } // 名称校验方法结束。

    public static int timeoutMs(Object raw) { // 校验请求中的毫秒超时值。
        if (!(raw instanceof Integer value) || value < 1 || value > 15000) { // 只接受 JSON 整数，范围为 1～15000。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 小数、字符串、空值及越界值均拒绝。
        } // 超时校验结束。
        return value; // 返回已经确认处于数据库约束范围内的值。
    } // 超时校验方法结束。

    public static EndpointTargetType targetType(Object raw) { // 校验创建请求中的目标类型。
        if (!(raw instanceof String value)) { // 类型必须来自 JSON 字符串。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝空值和其他 JSON 类型。
        } // 字段类型校验结束。
        return switch (value) { // 只接受契约中的两个精确枚举值。
            case "EXTERNAL" -> EndpointTargetType.EXTERNAL; // 外部接收端类型。
            case "SIMULATOR" -> EndpointTargetType.SIMULATOR; // 固定内部模拟器类型。
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST); // 拒绝未知类型，不记录原始输入。
        }; // 目标类型选择结束。
    } // 目标类型校验方法结束。
} // 接收端普通字段校验类结束。