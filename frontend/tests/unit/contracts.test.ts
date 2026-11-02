import { describe, expect, it } from 'vitest';
import { buildTestEventBody, parseResponse, payloadText } from '../../src/shared/api/json';
import { queryString, ApiError, canRetryRead } from '../../src/shared/api/client';
import { auditSchema, endpointSchema, overviewSchema, safeId } from '../../src/shared/api/schemas';
import { parseEventFilters } from '../../src/features/events/filters';
import { defaultRange, localDateToUtc, rangeError, utcToLocalDate } from '../../src/shared/time';
import { eventPathSafe, safeReturnTo } from '../../src/shared/navigation';
import { endSession, getGeneration, getSession, isCurrent, startSession } from '../../src/shared/api/session';

describe('无损 JSON 与完整请求约束', () => {
  it.each(['null', 'true', '0', '[]', '"hello"', '9007199254740993', '1.2345678901234567890123456789'])('接受任意合法值并保留词法：%s', (payload) => {
    const body = buildTestEventBody('order.paid', 1, payload);
    expect(body).toContain(`"payload":${payload}`);
    expect(body).not.toContain('eventId');
  });
  it('详情 payload 高精度数值不舍入', () => {
    const data = parseResponse('{"payload":{"n":9007199254740993,"rate":0.1234567890123456789},"id":12}') as { payload: unknown; id: number };
    expect(data.id).toBe(12);
    expect(payloadText(data.payload)).toContain('9007199254740993');
    expect(payloadText(data.payload)).toContain('0.1234567890123456789');
  });
  it('拒绝失真内部 ID，不通过字符串化修复', () => {
    expect(() => parseResponse('{"id":9223372036854775807}')).toThrow();
    expect(safeId.safeParse(9007199254740992).success).toBe(false);
  });
  it('拒绝空文本与非法 JSON', () => {
    expect(() => buildTestEventBody('a', 1, '')).toThrow();
    expect(() => buildTestEventBody('a', 1, '{')).toThrow();
    expect(() => buildTestEventBody('a', 0, 'null')).toThrow();
  });
  it('完整 UTF-8 体积含外层和中文', () => {
    const prefix = buildTestEventBody('x', 1, '""').length;
    expect(new TextEncoder().encode(buildTestEventBody('x', 1, `"${'a'.repeat(262144 - prefix)}"`)).byteLength).toBe(262144);
    expect(() => buildTestEventBody('x', 1, `"${'a'.repeat(262145 - prefix)}"`)).toThrow(/256 KiB/);
    expect(() => buildTestEventBody('x', 1, `"${'中'.repeat(90000)}"`)).toThrow(/256 KiB/);
  });
  it('类型不 trim，不改变精确匹配内容', () => {
    expect(buildTestEventBody(' order.paid ', 1, 'null')).toContain('"eventType":" order.paid "');
    expect(() => buildTestEventBody(' ', 1, 'null')).toThrow();
  });
});
describe('URL、时间与边界', () => {
  it('参数仅包含显式筛选，保留特殊字符', () => {
    const query = queryString({ eventId: '空 格%/A', empty: undefined, page: 1, size: 20 });
    expect(new URLSearchParams(query).get('eventId')).toBe('空 格%/A');
    expect(query).not.toContain('undefined');
  });
  it.each(['0', '01', '1e2', '-1', '1.5', ' '])('拒绝非法页码%s', (page) => {
    expect(parseEventFilters(new URLSearchParams({ page })).error).toBeTruthy();
  });
  it('分页默认1/20，不把UNKNOWN当作任务状态', () => {
    expect(parseEventFilters(new URLSearchParams()).filters).toEqual({ page: 1, size: 20 });
    expect(parseEventFilters(new URLSearchParams({ status: 'UNKNOWN' })).error).toBeTruthy();
  });
  it('本地日历日期往返并生成 UTC Z', () => {
    const utc = localDateToUtc('2026-11-02');
    expect(utc).toMatch(/Z$/);
    expect(utcToLocalDate(utc)).toBe('2026-11-02');
    expect(() => localDateToUtc('2026-02-30')).toThrow();
    const range = defaultRange(); expect(Date.parse(range.from)).toBeLessThan(Date.parse(range.to));
  });
  it('半开区间需from<to并拒绝偏移形式', () => {
    expect(rangeError('2026-11-02T00:00:00Z', '2026-11-02T00:00:00Z')).toBeTruthy();
    expect(rangeError('2026-11-02T00:00:00+08:00')).toBeTruthy();
    expect(rangeError('2026-11-01T00:00:00Z', '2026-11-02T00:00:00Z')).toBeUndefined();
    expect(rangeError('2026-11-02T00:00:00.000000001Z', '2026-11-02T00:00:00.000000002Z')).toBeUndefined();
  });
  it.each(['https://evil.test', '//evil.test', '/\\evil.test', '/login', '/events/../login', '/events\n'])('拒绝不安全返回地址%s', (value) => {
    expect(safeReturnTo(value)).toBe('/events');
  });
  it('保留合法筛选返回，限制后端不支持的ID详情路径', () => {
    expect(safeReturnTo('/events?status=PAUSED')).toBe('/events?status=PAUSED');
    expect(eventPathSafe('uuid-simple')).toBe(true);
    expect(eventPathSafe('a%b')).toBe(false);
    expect(eventPathSafe('a/b')).toBe(false);
  });
});
describe('响应契约与会话', () => {
  it('成功率null、0、0.8明确分开', () => {
    const statusCounts = { PENDING: 0, DELIVERING: 0, WAIT_RETRY: 0, PAUSED: 0, SUCCESS: 0, FINAL_FAILED: 0 };
    for (const rate of [null, 0, 0.8]) expect(overviewSchema.parse({ eventCount: 0, attemptCount: 0, successRate: rate, statusCounts }).successRate).toBe(rate);
  });
  it('审计对象必须为字符串，不能冒充外部eventId', () => {
    expect(auditSchema.safeParse({ id: 1, actorUserId: 1, action: 'EVENT_REDELIVERED', objectType: 'EVENT', objectId: '123', occurredAt: '2026-11-02T00:00:00Z', requestId: 'request-1' }).success).toBe(true);
  });
  it('端点普通响应剥离不应进入缓存的秘密', () => {
    const data = endpointSchema.parse({ id: 1, name: '虚构目标', targetType: 'SIMULATOR', url: null, timeoutMs: 5000, enabled: true, createdAt: '2026-11-02T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z', signingSecret: 'test-only' });
    expect(data).not.toHaveProperty('signingSecret');
  });
  it('GET只有限重试，权限/冲突/写入不明不重试', () => {
    expect(canRetryRead(0, new ApiError(500, 'INTERNAL_ERROR', '失败'))).toBe(true);
    expect(canRetryRead(1, new ApiError(500, 'INTERNAL_ERROR', '失败'))).toBe(false);
    for (const status of [400, 401, 403, 404, 409, 422]) expect(canRetryRead(0, new ApiError(status, 'ERROR', '失败'))).toBe(false);
    expect(canRetryRead(0, new ApiError(0, 'TIMEOUT', '未知', undefined, true))).toBe(false);
  });
  it('会话generation阻止旧请求清除新会话', () => {
    startSession({ username: 'test-user', token: 'in-memory-test-only', expiresAt: '2099-01-01T00:00:00Z' });
    const previous = getGeneration();
    startSession({ username: 'next-user', token: 'in-memory-test-only-2', expiresAt: '2099-01-01T00:00:00Z' });
    endSession(previous);
    expect(isCurrent(previous)).toBe(false);
    expect(getSession()?.username).toBe('next-user');
    endSession(); expect(getSession()).toBeNull();
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
  });
});
