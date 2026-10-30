package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.support.TestHttpReceiver;
import java.net.InetAddress;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

class WebServerBindingIntegrationTests {
    @Test void randomServerReallyBindsToSameIpv4LoopbackUsedByHttpClients() throws Exception {
        try (var context = new SpringApplicationBuilder(HookflowBackendApplication.class).run("--spring.profiles.active=skeleton", "--server.port=0");
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            assertEquals(InetAddress.getByName("127.0.0.1"), context.getBean(TomcatServletWebServerFactory.class).getAddress());
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var response = client.send(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
            assertTrue(response.body().contains("UP"));
        }
    }

    @Test void occupiedIpv4PortFailsStartupInsteadOfStartingOnDifferentAddressFamily() throws Exception {
        try (var receiver = TestHttpReceiver.start()) {
            var failure = assertThrows(org.springframework.context.ApplicationContextException.class, () -> {
                try (var unexpected = new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                        "--spring.profiles.active=skeleton", "--server.port=" + receiver.uri().getPort())) {
                    fail("已有IPv4监听时必须拒绝启动，不能只在IPv6启动后误投其他服务");
                }
            });
            boolean bindFailure = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.net.BindException) bindFailure = true;
            assertTrue(bindFailure, "必须是实际端口占用，不是其他启动失败");
        }
    }
}
