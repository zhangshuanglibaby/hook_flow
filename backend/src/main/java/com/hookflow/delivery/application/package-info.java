/**
 * 投递模块的应用层：编排任务领取、投递完成、重试、恢复和人工重投。
 * HTTP 请求必须在数据库领取事务提交之后执行。
 */
package com.hookflow.delivery.application;