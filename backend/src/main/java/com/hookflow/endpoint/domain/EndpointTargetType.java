package com.hookflow.endpoint.domain; // 接收端目标类型是接收端模块的业务概念，放在领域层。

public enum EndpointTargetType { // 用固定枚举限定允许的目标类型，避免使用任意字符串。
    EXTERNAL, // 表示由管理员配置外部接收地址的接收端。
    SIMULATOR // 表示使用系统固定内部目标、不得填写用户 URL 的接收端。
} // 接收端目标类型定义结束。