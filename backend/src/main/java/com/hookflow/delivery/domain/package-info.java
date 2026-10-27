/**
 * 投递模块的领域层：放置任务状态、尝试结果和重试判定规则。
 * 自动重试沿用当前轮次，人工重投才创建新的轮次。
 */
package com.hookflow.delivery.domain;