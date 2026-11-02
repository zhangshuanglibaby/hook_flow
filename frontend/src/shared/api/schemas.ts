import { z } from 'zod';

// 这里的 schema 是“网络响应检查器”。
// TypeScript 只能检查我们写代码时的类型，不能保证后端真的返回了这些字段；
// 所以收到 HTTP 响应后，还要用 Zod 再检查一遍。

// 后端使用 long 的地方，前端只接受 JavaScript 安全整数，避免 ID 发生精度丢失。
export const safeId = z.number().int().positive().refine(Number.isSafeInteger);
// 计数类字段必须是非负安全整数，例如 total、attemptCount。
const count = z.number().int().nonnegative().refine(Number.isSafeInteger);
// 后端时间必须是 UTC ISO 字符串，并且带 Z，表示不是浏览器本地时间。
const instant = z.string().refine((v) => /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v)));

// 接收端列表和详情使用的公开字段；这里没有 signingSecret。
export const endpointSchema = z.object({
  id: safeId,
  name: z.string(),
  targetType: z.enum(['EXTERNAL', 'SIMULATOR']),
  url: z.string().nullable(),
  timeoutMs: z.number().int().min(1).max(15000),
  enabled: z.boolean(),
  createdAt: instant,
  updatedAt: instant,
});

// 事件列表只使用后端承诺的摘要字段，不擅自加入尝试次数。
export const eventSummarySchema = z.object({
  eventId: z.string(),
  eventType: z.string(),
  endpointId: safeId,
  status: z.string(),
  latestRoundNo: count,
  createdAt: instant,
});

// 单次投递尝试的结果。UNKNOWN 和“确定失败”是两种不同含义。
export const attemptSchema = z.object({
  attemptNo: count,
  result: z.string(),
  startedAt: instant,
  endedAt: instant.nullable(),
  durationMs: count.nullable(),
  httpStatus: z.number().int().nullable(),
  responseExcerpt: z.string().nullable(),
  errorCode: z.string().nullable(),
  errorMessage: z.string().nullable(),
});

// 一轮投递包含多次尝试；自动重试发生在同一轮，不会自动新建轮次。
export const roundSchema = z.object({
  taskId: safeId,
  roundNo: count,
  status: z.string(),
  attemptCount: count,
  maxAttempts: count,
  nextRunAt: instant.nullable(),
  createdAt: instant,
  finishedAt: instant.nullable(),
  attempts: z.array(attemptSchema),
});

// 详情响应额外包含 payload 和全部历史轮次。
export const eventDetailSchema = eventSummarySchema
  .extend({ payload: z.unknown(), rounds: z.array(roundSchema) })
  .refine((value) => Object.hasOwn(value, 'payload'));

// 手动重投返回的新轮摘要。
export const taskSummarySchema = z.object({
  taskId: safeId,
  eventId: z.string(),
  eventType: z.string(),
  endpointId: safeId,
  roundNo: count,
  status: z.string(),
  attemptCount: count,
  maxAttempts: count,
  lastAttemptAt: instant.nullable(),
  createdAt: instant,
});

// 故障模拟器支持的四种模式。
export const modeSchema = z.enum(['ALWAYS_200', 'ALWAYS_500', 'FAIL_FIRST_N', 'PROCESSED_THEN_DELAY']);
export const configSchema = z.object({
  mode: modeSchema,
  failFirstN: z.number().int().min(0).max(10),
  delayMs: z.number().int().min(0).max(30000),
  updatedAt: instant,
});

// 模拟器接收记录中的 plannedStatus 是“计划返回值”，不等于发送端一定收到该响应。
export const receiptSchema = z.object({
  id: safeId,
  eventId: z.string(),
  receivedAt: instant,
  signatureValid: z.boolean(),
  plannedStatus: z.number().int().nullable(),
  duplicateProcessed: z.boolean(),
  configMode: z.string(),
});

// 审计记录只展示内部 ID，不把 objectId 当作外部 eventId 使用。
export const auditSchema = z.object({
  id: safeId,
  actorUserId: safeId,
  action: z.string(),
  objectType: z.string(),
  objectId: z.string(),
  occurredAt: instant,
  requestId: z.string(),
});

// 概览统计由后端聚合；成功率为 null 时表示没有可计算的终态事件。
export const overviewSchema = z.object({
  eventCount: count,
  attemptCount: count,
  successRate: z.number().min(0).max(1).nullable(),
  statusCounts: z.object({
    PENDING: count,
    DELIVERING: count,
    WAIT_RETRY: count,
    PAUSED: count,
    SUCCESS: count,
    FINAL_FAILED: count,
  }),
});

// 登录响应只包含短期内存会话所需字段。
export const loginSchema = z.object({ token: z.string().min(1), expiresAt: instant });

// 统一分页响应结构，避免每个页面重复写 page/size/total/items。
export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    page: z.number().int().positive(),
    size: z.number().int().min(1).max(100),
    total: count,
    items: z.array(item),
  });
}

// 从 schema 推导 TypeScript 类型，保证请求层和页面使用同一份字段定义。
export type Endpoint = z.infer<typeof endpointSchema>;
export type EventSummary = z.infer<typeof eventSummarySchema>;
export type EventDetail = z.infer<typeof eventDetailSchema>;
export type Round = z.infer<typeof roundSchema>;
export type Receipt = z.infer<typeof receiptSchema>;
export type Audit = z.infer<typeof auditSchema>;
export type SimulatorConfig = z.infer<typeof configSchema>;
export type SimulatorMode = z.infer<typeof modeSchema>;
export interface Page<T> { page: number; size: number; total: number; items: T[] }
