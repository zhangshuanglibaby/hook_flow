package com.hookflow.auth.infrastructure; // 将密码编码配置放在认证模块的基础设施层。

import org.springframework.context.annotation.Bean; // 用于把方法返回值注册为 Spring Bean。
import org.springframework.context.annotation.Configuration; // 声明此类提供 Spring 配置。
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; // 使用 Spring Security 提供的 BCrypt 实现。
import org.springframework.security.crypto.password.PasswordEncoder; // 让后续代码依赖密码编码器接口。

@Configuration(proxyBeanMethods = false) // 注册配置类；这里的方法不需要配置类代理。
public class PasswordEncoderConfiguration { // 集中提供管理员密码编码器。
    @Bean // 将编码器交给 Spring 管理，供初始化和登录逻辑注入。
    public PasswordEncoder passwordEncoder() { // 定义管理员密码编码器。
        return new BCryptPasswordEncoder(); // BCrypt 为每次编码生成盐值；数据库将保存编码结果而非明文。
    } // 密码编码器定义结束。
} // 配置类结束。   