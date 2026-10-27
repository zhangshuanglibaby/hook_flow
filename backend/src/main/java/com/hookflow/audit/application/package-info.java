/**
 * 审计模块的应用层：为成功的管理操作记录操作者、对象和时间。
 * 记录须加入调用方事务，不能把失败操作写成成功记录。
 */
package com.hookflow.audit.application;