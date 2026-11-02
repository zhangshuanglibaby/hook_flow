// 密码不属于会话缓存；只保存后端签发的 Token、过期时间和当前用户名。
export interface Session {
  token: string;
  expiresAt: string;
  username: string;
  // 每次登录或退出都会增加，防止旧请求修改新登录的会话。
  generation: number;
}

type StoredSession = Omit<Session, 'generation'>;
const storageKey = 'hookflow.admin-session.v1';

// 缓存只用于刷新恢复，不代表认证通过；后端仍会校验每次请求的 Token。
function validSession(value: unknown): value is StoredSession {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.token === 'string' && candidate.token.length > 0 && !/\s/.test(candidate.token)
    && typeof candidate.username === 'string' && candidate.username.trim().length > 0
    && [...candidate.username].length <= 64
    && typeof candidate.expiresAt === 'string'
    && Number.isFinite(Date.parse(candidate.expiresAt)) && Date.parse(candidate.expiresAt) > Date.now();
}

// sessionStorage 在同一标签页刷新后仍然存在，不使用长期保存的 localStorage。
// 浏览器禁用存储时退回内存会话，不能让存储异常阻断登录或退出。
function persistSession(value: StoredSession | null) {
  try {
    if (value) {
      const { token, expiresAt, username } = value;
      sessionStorage.setItem(storageKey, JSON.stringify({ token, expiresAt, username }));
    } else {
      sessionStorage.removeItem(storageKey);
    }
  } catch {
    // 隐私模式或存储配额限制下仍允许内存登录，但无法保证刷新恢复。
  }
}

// 模块初始化时先恢复缓存，再渲染路由，避免页面先跳登录再跳回工作台。
function restoreSession(): StoredSession | null {
  try {
    const text = sessionStorage.getItem(storageKey);
    if (text === null) return null;
    const value: unknown = JSON.parse(text);
    if (validSession(value)) {
      // 只取白名单字段，不把缓存里的额外字段带入会话。
      return { token: value.token, expiresAt: value.expiresAt, username: value.username };
    }
  } catch {
    // 损坏的 JSON 或不可用的存储都不能被当作有效登录。
  }
  persistSession(null);
  return null;
}

let generation = 0;
const restored = restoreSession();
let session: Session | null = restored ? { ...restored, generation } : null;
const listeners = new Set<() => void>();
const cleanups = new Set<() => void>();

export const getSession = () => session;
export const getGeneration = () => generation;

// React 组件订阅会话变化；组件卸载时调用返回的函数取消订阅。
export function subscribeSession(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

// 请求层和查询层在此注册清理工作，登录、退出时统一取消旧请求并清空缓存。
export function registerSessionCleanup(cleanup: () => void) {
  cleanups.add(cleanup);
  return () => { cleanups.delete(cleanup); };
}

function change(next: StoredSession | null) {
  generation += 1;
  session = next ? { token: next.token, expiresAt: next.expiresAt, username: next.username, generation } : null;
  persistSession(session);
  cleanups.forEach((cleanup) => cleanup());
  listeners.forEach((listener) => listener());
}

export function startSession(value: StoredSession) {
  if (!validSession(value)) throw new Error('登录会话无效或已过期，请重新登录。');
  change(value);
}

// 退出、过期、认证失败共用这个入口，同时清除内存与会话缓存。
// 旧请求的版本号不一致时，不允许清除新会话。
export function endSession(expectedGeneration = generation) {
  if (generation === expectedGeneration) change(null);
}

export function isCurrent(expectedGeneration: number) { return generation === expectedGeneration; }
