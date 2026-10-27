/**
 * 投递模块的基础设施层：放置任务持久化、HTTP 客户端和调度适配实现。
 * MySQL 是任务事实来源；可选 Redis 只用于加快唤醒。
 */
package com.hookflow.delivery.infrastructure;