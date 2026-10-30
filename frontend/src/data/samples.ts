// 这些类型只约束本地样例，不代表后端已经承诺相同字段。
import type { AttemptSample, AuditSample, EndpointSample, EventSample, ReceiptSample, RoundSample } from './types';

// 所有内容均为集中维护的只读展示样例；没有创建、更新、持久化或接口适配函数。
export const endpointSamples: readonly EndpointSample[] = [
  { id: 1, name: '积分服务', targetType: 'EXTERNAL', url: 'https://points.example.com/webhooks', timeoutMs: 5000, enabled: true },
  { id: 2, name: '内置故障模拟器', targetType: 'SIMULATOR', url: null, timeoutMs: 5000, enabled: true },
  { id: 3, name: '库存服务', targetType: 'EXTERNAL', url: 'https://inventory.example.com/events', timeoutMs: 5000, enabled: false },
];
// 固定失败尝试独立列出，时间包含原型规定的 10 秒、30 秒退避；不会随按钮生成。
const failedAttempts: readonly AttemptSample[] = [
  { attemptNo: 1, result: 'HTTP_FAILURE', startedAt: '2026-10-29T04:32:11.600Z', endedAt: '2026-10-29T04:32:11.685Z', durationMs: 85, httpStatus: 500, responseExcerpt: '{"error":"simulated_server_error"}', errorMessage: null },
  { attemptNo: 2, result: 'HTTP_FAILURE', startedAt: '2026-10-29T04:32:21.685Z', endedAt: '2026-10-29T04:32:21.787Z', durationMs: 102, httpStatus: 500, responseExcerpt: '{"error":"simulated_server_error"}', errorMessage: null },
  { attemptNo: 3, result: 'HTTP_FAILURE', startedAt: '2026-10-29T04:32:51.787Z', endedAt: '2026-10-29T04:32:51.878Z', durationMs: 91, httpStatus: 500, responseExcerpt: '{"error":"simulated_server_error"}', errorMessage: null },
];
// 静态失败轮仅由明确声明的数据组成，不使用状态生成器推算历史。
const failedRounds: readonly RoundSample[] = [
  { roundNo: 1, status: 'FINAL_FAILED', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 5000, attempts: failedAttempts },
];
// 预先写好的超时样例强调“结果未知”，不能据此判断接收端未处理。
const unknownRounds: readonly RoundSample[] = [
  { roundNo: 1, status: 'FINAL_FAILED', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 5000, attempts: [
    { attemptNo: 1, result: 'UNKNOWN', startedAt: '2026-10-29T03:32:11.500Z', endedAt: '2026-10-29T03:32:16.500Z', durationMs: 5000, httpStatus: null, responseExcerpt: null, errorMessage: '请求超时，接收端可能已处理；未能确认响应。' },
    { attemptNo: 2, result: 'UNKNOWN', startedAt: '2026-10-29T03:32:26.500Z', endedAt: '2026-10-29T03:32:31.500Z', durationMs: 5000, httpStatus: null, responseExcerpt: null, errorMessage: '请求超时，接收端可能已处理；未能确认响应。' },
    { attemptNo: 3, result: 'UNKNOWN', startedAt: '2026-10-29T03:33:01.500Z', endedAt: '2026-10-29T03:33:06.500Z', durationMs: 5000, httpStatus: null, responseExcerpt: null, errorMessage: '请求超时，接收端可能已处理；未能确认响应。' },
  ] },
];
// JSON 展示原文只包含虚构订单，页面不会依据输入替换此样例。
export const payloadSample = '{\n  "orderId": "demo-order-1038",\n  "amount": 128,\n  "currency": "CNY"\n}';
// 八条静态事件覆盖六状态、多轮、UNKNOWN、STARTED、LOCAL_FAILURE 和 0 ms。
export const eventSamples: readonly EventSample[] = [
  { eventId: 'evt-demo-1038', eventType: 'order.paid', endpointId: 2, status: 'WAIT_RETRY', latestRoundNo: 1, attemptCount: 1, maxAttempts: 3, createdAt: '2026-10-29T06:32:10Z', lastAttemptAt: '2026-10-29T06:32:11.600Z', payloadText: payloadSample, rounds: [
    { roundNo: 1, status: 'WAIT_RETRY', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 5000, attempts: [
      { attemptNo: 1, result: 'HTTP_FAILURE', startedAt: '2026-10-29T06:32:11.600Z', endedAt: '2026-10-29T06:32:11.686Z', durationMs: 86, httpStatus: 500, responseExcerpt: '{"error":"simulated_server_error"}', errorMessage: null },
    ] },
  ] },
  { eventId: 'evt-demo-1037', eventType: 'order.paid', endpointId: 1, status: 'SUCCESS', latestRoundNo: 1, attemptCount: 1, maxAttempts: 3, createdAt: '2026-10-29T05:32:10Z', lastAttemptAt: '2026-10-29T05:32:11.600Z', payloadText: payloadSample, rounds: [
    { roundNo: 1, status: 'SUCCESS', maxAttempts: 3, snapshotUrl: 'https://points.example.com/webhooks', snapshotTimeoutMs: 5000, attempts: [
      { attemptNo: 1, result: 'SUCCESS', startedAt: '2026-10-29T05:32:11.600Z', endedAt: '2026-10-29T05:32:11.724Z', durationMs: 124, httpStatus: 200, responseExcerpt: '{"accepted":true}', errorMessage: null },
    ] },
  ] },
  { eventId: 'evt-demo-1036', eventType: 'order.created', endpointId: 2, status: 'FINAL_FAILED', latestRoundNo: 1, attemptCount: 3, maxAttempts: 3, createdAt: '2026-10-29T04:32:10Z', lastAttemptAt: '2026-10-29T04:32:51.787Z', payloadText: payloadSample, rounds: failedRounds },
  { eventId: 'evt-demo-1035', eventType: 'payment.confirmed', endpointId: 2, status: 'FINAL_FAILED', latestRoundNo: 1, attemptCount: 3, maxAttempts: 3, createdAt: '2026-10-29T03:32:10Z', lastAttemptAt: '2026-10-29T03:33:01.500Z', payloadText: 'null', rounds: unknownRounds },
  { eventId: 'evt-demo-1034', eventType: 'inventory.reserved', endpointId: 3, status: 'PAUSED', latestRoundNo: 1, attemptCount: 1, maxAttempts: 3, createdAt: '2026-10-29T02:32:10Z', lastAttemptAt: '2026-10-29T02:32:11Z', payloadText: '["demo-item-1", "demo-item-2"]', rounds: [
    { roundNo: 1, status: 'PAUSED', maxAttempts: 3, snapshotUrl: 'https://inventory.example.com/events', snapshotTimeoutMs: 5000, attempts: [
      { attemptNo: 1, result: 'UNKNOWN', startedAt: '2026-10-29T02:32:11Z', endedAt: '2026-10-29T02:32:16Z', durationMs: 5000, httpStatus: null, responseExcerpt: null, errorMessage: '连接中断，响应未确认。接收端停用后未发任务暂停。' },
    ] },
  ] },
  { eventId: 'evt-demo-1033', eventType: 'order.paid', endpointId: 1, status: 'PENDING', latestRoundNo: 1, attemptCount: 0, maxAttempts: 3, createdAt: '2026-10-29T01:32:10Z', lastAttemptAt: null, payloadText: payloadSample, rounds: [
    { roundNo: 1, status: 'PENDING', maxAttempts: 3, snapshotUrl: 'https://points.example.com/webhooks', snapshotTimeoutMs: 5000, attempts: [] },
  ] },
  { eventId: 'evt-demo-1032', eventType: 'order.created', endpointId: 2, status: 'DELIVERING', latestRoundNo: 1, attemptCount: 1, maxAttempts: 3, createdAt: '2026-10-29T00:32:10Z', lastAttemptAt: '2026-10-29T00:32:11Z', payloadText: payloadSample, rounds: [
    { roundNo: 1, status: 'DELIVERING', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 5000, attempts: [
      { attemptNo: 1, result: 'STARTED', startedAt: '2026-10-29T00:32:11Z', endedAt: null, durationMs: null, httpStatus: null, responseExcerpt: null, errorMessage: null },
    ] },
  ] },
  { eventId: 'evt-demo-1031', eventType: 'order.paid', endpointId: 2, status: 'SUCCESS', latestRoundNo: 2, attemptCount: 2, maxAttempts: 3, createdAt: '2026-10-28T23:32:10Z', lastAttemptAt: '2026-10-28T23:34:20.086Z', payloadText: payloadSample, rounds: [
    { roundNo: 1, status: 'FINAL_FAILED', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 3000, attempts: [
      { attemptNo: 1, result: 'LOCAL_FAILURE', startedAt: '2026-10-28T23:32:11Z', endedAt: '2026-10-28T23:32:11Z', durationMs: 0, httpStatus: null, responseExcerpt: null, errorMessage: '目标地址未通过安全校验，本次请求未发送。' },
    ] },
    { roundNo: 2, status: 'SUCCESS', maxAttempts: 3, snapshotUrl: null, snapshotTimeoutMs: 5000, attempts: [
      { attemptNo: 1, result: 'HTTP_FAILURE', startedAt: '2026-10-28T23:34:10Z', endedAt: '2026-10-28T23:34:10.086Z', durationMs: 86, httpStatus: 500, responseExcerpt: '{"error":"simulated_server_error"}', errorMessage: null },
      { attemptNo: 2, result: 'SUCCESS', startedAt: '2026-10-28T23:34:20.086Z', endedAt: '2026-10-28T23:34:20.196Z', durationMs: 110, httpStatus: 200, responseExcerpt: '{"accepted":true}', errorMessage: null },
    ] },
  ] },
];
// 概览是独立写定的展示输入，绝不遍历 eventSamples 计算假实时统计。
export const overviewSample = {
  from: '2026-10-28', to: '2026-10-30', eventCount: 8, successRateText: '50.0', terminalCount: 4, attemptCount: 13,
  statusCounts: { PENDING: 1, DELIVERING: 1, WAIT_RETRY: 1, PAUSED: 1, SUCCESS: 2, FINAL_FAILED: 2 },
} as const;
// 接收数据是固定历史说明，模式切换或按钮不会改写它们。
export const receiptSamples: readonly ReceiptSample[] = [
  { id: 1, eventId: 'evt-demo-1038', receivedAt: '2026-10-29T06:32:11.600Z', configMode: 'FAIL_FIRST_N', plannedStatus: 500, signatureValid: true, duplicateProcessed: false },
  { id: 2, eventId: 'evt-demo-1031', receivedAt: '2026-10-28T23:34:20.086Z', configMode: 'FAIL_FIRST_N', plannedStatus: 200, signatureValid: true, duplicateProcessed: true },
];
// 固定审计仅展示已承诺的内部 ID，不合成当前用户的任何操作记录。
export const auditSamples: readonly AuditSample[] = [
  { id: 6, actorUserId: 1, action: 'ENDPOINT_UPDATE', objectType: 'ENDPOINT', objectId: 1, occurredAt: '2026-10-29T06:00:00Z', requestId: 'req-static-1029' },
  { id: 5, actorUserId: 1, action: 'SIMULATOR_CONFIG_UPDATE', objectType: 'SIMULATOR', objectId: 1, occurredAt: '2026-10-29T05:30:00Z', requestId: 'req-static-1028' },
  { id: 4, actorUserId: 1, action: 'ENDPOINT_DISABLE', objectType: 'ENDPOINT', objectId: 3, occurredAt: '2026-10-29T05:00:00Z', requestId: 'req-static-1027' },
  { id: 3, actorUserId: 1, action: 'EVENT_REDELIVER', objectType: 'EVENT', objectId: 8, occurredAt: '2026-10-29T04:30:00Z', requestId: 'req-static-1026' },
  { id: 2, actorUserId: 1, action: 'ENDPOINT_ENABLE', objectType: 'ENDPOINT', objectId: 1, occurredAt: '2026-10-29T04:00:00Z', requestId: 'req-static-1025' },
  { id: 1, actorUserId: 1, action: 'ENDPOINT_CREATE', objectType: 'ENDPOINT', objectId: 2, occurredAt: '2026-10-29T03:30:00Z', requestId: 'req-static-1024' },
];
