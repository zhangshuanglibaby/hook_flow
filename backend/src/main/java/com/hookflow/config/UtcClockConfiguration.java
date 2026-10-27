package com.hookflow.config; // 放在现有公共配置包中，供各业务模块注入使用。

import java.time.Clock; // 使用 Java 标准库的可替换时钟类型。
import org.springframework.context.annotation.Bean; // 将方法返回的时钟注册为 Spring Bean。
import org.springframework.context.annotation.Configuration; // 声明此类提供应用配置。

@Configuration(proxyBeanMethods = false) // 此配置不需要代理配置方法之间的调用。
public class UtcClockConfiguration { // 集中提供应用使用的 UTC 时钟。
    @Bean // 让其他组件以后可以通过构造器注入 Clock。
    public Clock clock() { // 定义唯一的应用时钟入口。
        return Clock.systemUTC(); // 使用 UTC 时区；测试时可替换为固定时钟。
    } // 时钟 Bean 定义结束。
} // 配置类结束。