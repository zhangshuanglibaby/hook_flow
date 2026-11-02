import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const key = 'hookflow.admin-session.v1';
const fixture = { username: 'test-admin', token: 'test-session-token', expiresAt: '2099-01-01T00:00:00Z' };

// 使用测试会话验证存储生命周期，不伪造 HTTP 或绕过真实后端认证。
describe('登录会话缓存', () => {
  beforeEach(() => { sessionStorage.clear(); localStorage.clear(); vi.resetModules(); });
  afterEach(() => { vi.restoreAllMocks(); sessionStorage.clear(); localStorage.clear(); });

  it('登录只缓存白名单字段，不缓存密码、generation或额外信息', async () => {
    const session = await import('../../src/shared/api/session');
    const value = { ...fixture, password: 'test-only-password', secret: 'not-for-storage' };
    session.startSession(value);
    expect(JSON.parse(sessionStorage.getItem(key)!)).toEqual(fixture);
    expect(session.getSession()).not.toHaveProperty('password');
    expect(localStorage.length).toBe(0);
  });

  it('模块重新初始化后恢复有效会话，模拟页面刷新', async () => {
    const session = await import('../../src/shared/api/session');
    session.startSession(fixture);
    vi.resetModules();
    const refreshed = await import('../../src/shared/api/session');
    expect(refreshed.getSession()).toMatchObject(fixture);
  });

  it.each([
    '{', 'null', '[]',
    JSON.stringify({ ...fixture, token: '' }),
    JSON.stringify({ ...fixture, token: 123 }),
    JSON.stringify({ ...fixture, expiresAt: '2000-01-01T00:00:00Z' }),
    JSON.stringify({ ...fixture, expiresAt: 'invalid' }),
    JSON.stringify({ ...fixture, username: ' ' }),
  ])('忽略并清除损坏或过期的缓存：%s', async (text) => {
    sessionStorage.setItem(key, text);
    const session = await import('../../src/shared/api/session');
    expect(session.getSession()).toBeNull();
    expect(sessionStorage.getItem(key)).toBeNull();
  });

  it('旧请求不能清除新会话，正常退出同时清除缓存', async () => {
    const session = await import('../../src/shared/api/session');
    session.startSession(fixture);
    const previous = session.getGeneration();
    session.startSession({ ...fixture, username: 'next-admin' });
    session.endSession(previous);
    expect(JSON.parse(sessionStorage.getItem(key)!).username).toBe('next-admin');
    session.endSession();
    expect(session.getSession()).toBeNull();
    expect(sessionStorage.getItem(key)).toBeNull();
    vi.resetModules();
    expect((await import('../../src/shared/api/session')).getSession()).toBeNull();
  });

  it('存储不可用时仍支持内存登录与退出', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    const session = await import('../../src/shared/api/session');
    expect(() => session.startSession(fixture)).not.toThrow();
    expect(session.getSession()?.username).toBe(fixture.username);
    expect(() => session.endSession()).not.toThrow();
    expect(session.getSession()).toBeNull();
  });
});
