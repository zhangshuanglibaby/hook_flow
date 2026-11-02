import type { z } from 'zod';
import { endSession, getGeneration, getSession, isCurrent, registerSessionCleanup } from './session';
import { parseResponse } from './json';

// 统一描述 API 错误，页面可以根据 HTTP 状态码、业务 code 和 requestId 给出准确提示。
export class ApiError extends Error {
  constructor(
    public httpStatus: number,
    public code: string,
    message: string,
    public requestId?: string,
    // true 表示写操作的最终结果不确定，不能直接让用户再次提交。
    public uncertain = false,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// API 地址只能是同源路径，避免把管理员 Token 发送到未审核的外部地址。
const configuredBase = import.meta.env.VITE_API_BASE_URL || '/api/v1';
if (!configuredBase.startsWith('/') || configuredBase.startsWith('//') || configuredBase.includes('?') || configuredBase.includes('#')) {
  throw new Error('API 基址必须为同源绝对路径。');
}
const base = configuredBase.replace(/\/$/, '');

// 保存当前所有进行中的请求。退出或切换账号时会统一取消它们。
const active = new Set<AbortController>();
registerSessionCleanup(() => {
  active.forEach((controller) => controller.abort());
  active.clear();
});

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT';
  body?: string;
  signal?: AbortSignal;
  // 登录接口没有管理员 Token，所以通过 public 标记明确区分。
  public?: boolean;
}

// 所有前端请求都从这里发出，页面不直接调用 fetch。
export async function request<T>(path: string, schema: z.ZodType<T> | null, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const writing = method !== 'GET';
  const generation = getGeneration();
  const session = getSession();

  // 受保护接口没有登录会话时，直接阻止请求，不把空 Token 发给后端。
  if (!options.public && !session) throw new ApiError(401, 'UNAUTHORIZED', '请重新登录。');
  // 前端先检查过期时间，减少把已经过期的 Token 发到服务端。
  if (session && Date.parse(session.expiresAt) <= Date.now() && !options.public) {
    endSession(generation);
    throw new ApiError(401, 'UNAUTHORIZED', '登录已过期，请重新登录。');
  }

  const controller = new AbortController();
  active.add(controller);
  // 管理接口的客户端保护超时是15秒，不等于后端投递目标的超时配置。
  const timeout = setTimeout(() => controller.abort(new DOMException('Timeout', 'TimeoutError')), 15000);
  // 同时监听页面主动取消和统一超时。
  const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal;

  try {
    const response = await fetch(`${base}${path}`, {
      method,
      signal,
      // 不使用 Cookie，请求仍从会话模块获取 Bearer Token（刷新时已恢复缓存）。
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      headers: {
        Accept: 'application/json',
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(!options.public && session ? { Authorization: `Bearer ${session.token}` } : {}),
      },
      body: options.body,
    });

    // 如果用户已经退出或换账号，旧请求的结果不能继续更新当前页面。
    if (!isCurrent(generation)) throw new DOMException('会话已切换', 'AbortError');
    const requestId = response.headers.get('X-Request-Id') ?? undefined;

    // 204 表示没有响应体，通常用于 logout。带 schema 的请求不接受空响应。
    if (response.status === 204) {
      if (schema) throw new ApiError(204, 'CONTRACT_MISMATCH', '接口返回了空响应，无法确认记录内容。', requestId, writing);
      return undefined as T;
    }

    const text = await response.text();
    if (!isCurrent(generation)) throw new DOMException('会话已切换', 'AbortError');

    let data: unknown;
    try {
      data = parseResponse(text);
    } catch {
      // 非 JSON 的错误常见于代理或网关异常，不能把 HTML 当业务数据展示。
      if (!response.ok) throw new ApiError(response.status, 'INVALID_RESPONSE', '服务暂不可用，代理或后端未返回有效 JSON。', requestId, writing && response.status >= 500);
      throw new ApiError(response.status, 'CONTRACT_MISMATCH', '响应格式不符合契约，或数字超出安全范围。请保留请求 ID 联系后端。', requestId, writing);
    }

    if (!response.ok) {
      const error = data && typeof data === 'object' ? data as Record<string, unknown> : {};
      const code = typeof error.code === 'string' ? error.code : 'HTTP_ERROR';
      const message = typeof error.message === 'string' ? error.message : '请求未能完成，请重试查询。';
      // 只有后端明确返回 401 UNAUTHORIZED 才清除会话；403 不代表登录过期。
      if (!options.public && response.status === 401 && code === 'UNAUTHORIZED') endSession(generation);
      throw new ApiError(response.status, code, message, typeof error.requestId === 'string' ? error.requestId : requestId, writing && response.status >= 500);
    }

    // 非204请求必须有 schema，防止页面把未验证的网络 JSON 当作可靠数据。
    if (!schema) throw new ApiError(response.status, 'CONTRACT_MISMATCH', '服务端未返回约定的 204，尚未确认会话撤销。', requestId, writing);
    const result = schema.safeParse(data);
    if (!result.success) throw new ApiError(response.status, 'CONTRACT_MISMATCH', '响应字段不符合接口契约，请保留请求 ID 联系后端。', requestId, writing);
    return result.data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    // 主动取消或会话切换不应该显示成“网络失败”。
    if (signal.aborted && (options.signal?.aborted || !isCurrent(generation))) throw new DOMException('请求已取消', 'AbortError');
    // 写请求超时不能自动重发，因为服务器可能已经完成了写入。
    throw new ApiError(0, signal.aborted ? 'TIMEOUT' : 'NETWORK_ERROR', writing ? '请求结果尚未确认。请先查询已有记录，不要直接重复提交。' : '无法连接后端，请检查开发服务后重新加载。', undefined, writing);
  } finally {
    clearTimeout(timeout);
    active.delete(controller);
  }
}

// 把筛选对象转换成 URL 查询参数；undefined 和空字符串不会被发送。
export function queryString(values: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  const text = params.toString();
  return text ? `?${text}` : '';
}

// GET 查询遇到短暂网络/5xx错误最多重试一次；写请求或业务错误不重试。
export function canRetryRead(failureCount: number, error: Error): boolean {
  return failureCount < 1 && error instanceof ApiError && (error.httpStatus === 0 || error.httpStatus >= 500) && !error.uncertain;
}
