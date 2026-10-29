package com.hookflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles; // 导入为测试指定配置组的注解。

@SpringBootTest
@ActiveProfiles("skeleton") // 让这个测试类使用临时的无数据库配置。
class HookflowBackendApplicationTests {
	@Autowired
	private Environment environment;

	@Test
	void contextLoads() {
	}

	@Test
	void httpClientHeaderAndWireLoggingStayDisabled() {
		assertEquals("OFF", environment.getRequiredProperty("logging.level.org.apache.hc.client5.http.headers"));
		assertEquals("OFF", environment.getRequiredProperty("logging.level.org.apache.hc.client5.http.wire"));
		assertEquals(Level.OFF, ((Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.headers"))
				.getEffectiveLevel());
		assertEquals(Level.OFF, ((Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.wire"))
				.getEffectiveLevel());
	}

}
