/**
 * 模拟器模块的应用层：编排故障模式、接收记录与按事件 ID 幂等处理。
 * 模拟响应延迟时，不得持续占用数据库事务。
 */
package com.hookflow.simulator.application; 