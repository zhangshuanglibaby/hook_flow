import { request, queryString } from './client';
import {
  auditSchema,
  configSchema,
  endpointSchema,
  eventDetailSchema,
  eventSummarySchema,
  loginSchema,
  overviewSchema,
  pageSchema,
  receiptSchema,
  taskSummarySchema,
} from './schemas';
import { z } from 'zod';

// 这里集中定义所有后端接口，页面只调用 api.xxx，不直接拼接 URL 或请求头。
// 好处是：认证、响应校验和参数格式都由 shared/api 统一管理。
export const api = {
  // 登录是公开接口，成功后只返回内存会话需要的 token 和过期时间。
  login: (username: string, password: string, signal?: AbortSignal) => request(
    '/auth/login',
    loginSchema,
    { method: 'POST', public: true, body: JSON.stringify({ username, password }), signal },
  ),

  // 退出接口成功时返回 204，没有响应体。
  logout: () => request('/auth/logout', null, { method: 'POST' }),

  // 事件列表使用服务端筛选和分页，不在浏览器里从当前页数据拼出全量结果。
  events: (filters: Record<string, string | number | undefined>, signal?: AbortSignal) => request(
    `/admin/events${queryString(filters)}`,
    pageSchema(eventSummarySchema),
    { signal },
  ),
  // 事件详情包含 payload、全部轮次和每轮尝试。
  event: (id: string, signal?: AbortSignal) => request(
    `/admin/events/${encodeURIComponent(id)}`,
    eventDetailSchema,
    { signal },
  ),
  // 管理员测试事件不能传 eventId，由后端生成 UUID。
  createEvent: (body: string) => request(
    '/admin/events/test',
    eventSummarySchema.extend({ duplicate: z.boolean() }),
    { method: 'POST', body },
  ),
  // 手动重投使用事件 ID创建新一轮，旧轮由后端只读保留。
  redeliver: (id: string) => request(
    `/admin/events/${encodeURIComponent(id)}/redeliver`,
    taskSummarySchema,
    { method: 'POST' },
  ),

  // 接收端列表分页查询，供管理页和选择器复用。
  endpoints: (page: number, size = 20, signal?: AbortSignal) => request(
    `/admin/endpoints${queryString({ page, size })}`,
    pageSchema(endpointSchema),
    { signal },
  ),
  endpoint: (id: number, signal?: AbortSignal) => request(`/admin/endpoints/${id}`, endpointSchema, { signal }),
  // 创建外部接收端时，响应可能只在这一次包含 signingSecret。
  createEndpoint: (body: string) => request(
    '/admin/endpoints',
    endpointSchema
      .extend({ signingSecret: z.string().min(1).optional() })
      .refine((value) => value.targetType !== 'EXTERNAL' || Boolean(value.signingSecret)),
    { method: 'POST', body },
  ),
  // 编辑接口只允许 name/url/timeoutMs，页面负责只发送真实变更字段。
  updateEndpoint: (id: number, body: string) => request(`/admin/endpoints/${id}`, endpointSchema, { method: 'PATCH', body }),
  // 启用和停用必须走后端提供的专用空体接口。
  toggleEndpoint: (id: number, enabled: boolean) => request(
    `/admin/endpoints/${id}/${enabled ? 'enable' : 'disable'}`,
    endpointSchema,
    { method: 'POST' },
  ),

  // 读取和保存全局故障模拟器配置。
  config: (signal?: AbortSignal) => request('/admin/simulator/config', configSchema, { signal }),
  saveConfig: (body: string) => request('/admin/simulator/config', configSchema, { method: 'PUT', body }),
  // 接收记录只有分页，没有契约外的 eventId 筛选参数。
  receipts: (page: number, size: number, signal?: AbortSignal) => request(
    `/admin/simulator/requests${queryString({ page, size })}`,
    pageSchema(receiptSchema),
    { signal },
  ),

  // 概览的两个时间边界都必须传给后端，统计由服务端计算。
  overview: (from: string, to: string, signal?: AbortSignal) => request(
    `/admin/overview${queryString({ from, to })}`,
    overviewSchema,
    { signal },
  ),
  // 审计接口只读分页，不在浏览器里自行生成审计记录。
  audit: (page: number, size: number, signal?: AbortSignal) => request(
    `/admin/audit-logs${queryString({ page, size })}`,
    pageSchema(auditSchema),
    { signal },
  ),
};
