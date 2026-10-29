package com.hookflow.delivery.infrastructure; // 将模拟器内部目标放在投递基础设施层。

import java.net.URI; // 表示固定的内部 HTTP 目标。
import java.util.Objects; // 检查必需的应用环境。
import org.springframework.core.env.Environment; // 读取应用自身的监听端口。
import org.springframework.stereotype.Component; // 将固定路由交由 Spring 管理。

@Component // 供后续单次投递客户端注入。
public final class SimulatorInternalTargetRoute { // 仅生成系统固定的模拟器内部目标。
    public static final String PATH = "/internal/hookflow/simulator/receive"; // 固定接收路径，留给 P8 实现接收接口。
    private final Environment environment; // 只读取应用配置，不读取接收端 URL。

    public SimulatorInternalTargetRoute(Environment environment) { // 注入当前应用的环境。
        this.environment = Objects.requireNonNull(environment, "应用环境缺失"); // 禁止缺少端口来源。
    }

    public URI targetUri() { // 在实际调用时取得应用已经确定的监听端口。
        Integer port = environment.getProperty("local.server.port", Integer.class); // 优先使用随机端口启动后的实际端口。
        if (port == null) { // 正常固定端口启动时没有随机端口属性。
            port = environment.getProperty("server.port", Integer.class, 8080); // 使用配置端口，未配置则用默认端口。
        }
        if (port == null || port < 1 || port > 65535) { // 未启动或端口非法时不能猜测目标。
            throw new IllegalStateException("模拟器内部监听端口不可用"); // 阻止发送到不确定的地址。
        }
        return URI.create("http://127.0.0.1:" + port + PATH); // 仅拼接固定本机主机、已校验端口和固定路径。
    }
}