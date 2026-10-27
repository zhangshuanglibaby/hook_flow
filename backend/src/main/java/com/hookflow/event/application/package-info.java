/**
 * 事件模块的应用层：编排事件接收、去重、查询与首轮任务创建。
 * 新事件和首轮任务必须在同一事务提交后才能返回接收成功。
 */
package com.hookflow.event.application;