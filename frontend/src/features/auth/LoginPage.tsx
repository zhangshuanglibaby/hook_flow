import { useEffect, useRef, useState } from 'react';
import { Button, Form, Input } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import loginBackground from '../../assets/images/bg.png';
import { textError } from '../../shared/format';
import { api } from '../../shared/api/resources';
import { getGeneration, isCurrent, startSession } from '../../shared/api/session';
import { ErrorNotice, WarningNotice } from '../../shared/ui';
import { useSession } from '../../shared/query';
import { safeReturnTo } from '../../shared/navigation';

// 仅 dev 环境预填本地演示账号；仍须真实后端认证，不是跳过登录。
const developmentDefaults = import.meta.env.DEV && import.meta.env.VITE_APP_ENV === 'dev'
  ? { username: 'admin', password: '123456' }
  : undefined;

export function LoginPage() {
  // 会话模块已在启动时恢复有效缓存；已登录时直接返回目标页面。
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [form] = Form.useForm();
  // 只保存“是否显示密码”这个界面状态，不保存密码本身。
  const [visible, setVisible] = useState(false);
  // busy 用于禁用表单，防止用户在登录请求期间重复提交。
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  // inFlight 比按钮 disabled 更底层，确保键盘提交也不会重复发请求。
  const inFlight = useRef(false);
  // 页面卸载时取消还没完成的登录请求。
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);

  // 从路由 state 读取过期提示和安全 returnTo。
  const state = location.state as { returnTo?: string; expired?: boolean } | null;
  const destination = safeReturnTo(state?.returnTo);
  if (session) return <Navigate to={destination} replace />;

  async function login(values: { username: string; password: string }) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    // 记录请求开始时的会话版本，防止迟到响应污染新会话。
    const generation = getGeneration();
    controller.current = new AbortController();
    try {
      const result = await api.login(values.username, values.password, controller.current.signal);
      if (!isCurrent(generation) || controller.current.signal.aborted) return;
      // 后端返回已过期时间时，不创建一个立即失效的前端会话。
      if (Date.parse(result.expiresAt) <= Date.now()) throw new Error('服务器返回的会话已过期，请检查系统时间。');
      // 登录成功后清掉密码输入，但不把密码写入任何持久化存储。
      form.setFieldValue('password', '');
      startSession({ ...result, username: values.username });
      navigate(destination, { replace: true });
    } catch (cause) {
      // 用户主动离开页面造成的取消不需要显示错误。
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof Error ? cause : new Error('登录失败，请重试。'));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  // 账号和密码使用后端相同的 Unicode 码点范围进行前端即时校验。
  const rule = (max: number) => ({ validator: async (_: unknown, value: string) => {
    const validationError = textError(value ?? '', max);
    if (validationError || !value.trim()) throw new Error(validationError ?? '不能全部为空白。');
  } });
  return <div className="login-layout"><section className="login-story" aria-label="HookFlow 事件投递平台介绍" style={{ backgroundImage: `url("${loginBackground}")` }}><p className="sr-only">连接业务系统与 HTTP 接收端，追踪每一轮事件投递。</p></section><section className="login-form-area"><div className="login-form-wrap"><span className="outline-tag">{import.meta.env.VITE_APP_ENV === 'pro' ? '管理工作台' : '开发环境'}</span><h2>登录工作台</h2><p>使用专用管理员账号，进入事件投递工作台。</p>{state?.expired && <WarningNotice message="登录已过期，请重新登录。" />}<ErrorNotice error={error} /><Form form={form} layout="vertical" className="login-form" initialValues={developmentDefaults} onFinish={login} autoComplete="on" disabled={busy}>
    <Form.Item label="账号" name="username" rules={[rule(64)]}><Input autoComplete="username" placeholder="输入管理员账号" /></Form.Item>
    <Form.Item label="密码" name="password" rules={[rule(1024)]}><Input type={visible ? 'text' : 'password'} autoComplete="current-password" placeholder="输入密码" suffix={<Button type="text" size="small" aria-label={visible ? '隐藏密码' : '显示密码'} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? '隐藏' : '显示'}</Button>} /></Form.Item>
    <Button className="login-submit" type="primary" htmlType="submit" loading={busy} block>登录</Button>
  </Form><p className="subtle">登录状态保留在当前标签页，刷新可继续使用；过期后需要重新登录。密码不会缓存。</p></div><footer>HookFlow · 只使用虚构业务数据进行开发联调</footer></section></div>;
}
