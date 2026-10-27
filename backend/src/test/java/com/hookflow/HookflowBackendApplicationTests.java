package com.hookflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles; // 导入为测试指定配置组的注解。

@SpringBootTest
@ActiveProfiles("skeleton") // 让这个测试类使用临时的无数据库配置。
class HookflowBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
