// 登录页面只允许输入及密码可见切换，不比较固定凭据、不生成 Token。
import { useState } from 'react';
import { Form, Input, Button } from 'antd';
import { ApiOutlined, FileTextOutlined, ReloadOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { Brand } from '../../layouts/WorkspaceLayout';
import { StaticNotice, StatusTag } from '../../shared/ui';
import { textError } from '../../shared/format';

// 表单字段校验只改善本地输入体验，不意味着身份认证。
export function LoginPage() {
  // 只保存密码是否可见，不额外保存、打印或比较密码内容。
  const [passwordVisible, setPasswordVisible] = useState(false);
  // 显示按钮使用原生可聚焦的 Button，确保键盘也能切换。
  function togglePassword() {
    // 通过上一次状态取反切换输入类型，不更改密码的实际值。
    setPasswordVisible((visible) => !visible);
  }
  // 校验账号非空且不超过 64 个 Unicode 码点。
  async function validateUsername(_: unknown, value: string) {
    // 不预填演示账号，避免固定凭据进入工程。
    const error = textError(value ?? '', 64);
    // 将格式错误交给表单，保持输入不跳页。
    if (error) throw new Error(error);
  }
  // 校验密码的长度，不 trim、不比较密码值、不记录输入。
  async function validatePassword(_: unknown, value: string) {
    // 密码中的空格属于用户输入的一部分，不擅自移除。
    const error = textError(value ?? '', 1024);
    // 只显示格式反馈，不伪造“账号或密码错误”的后端结论。
    if (error) throw new Error(error);
  }
  // 双栏结构、产品路径及成功样例保持原型视觉，窄屏隐藏说明栏。
  return <div className="login-layout"><section className="login-story"><Brand /><div className="login-story-content"><span className="outline-tag">Webhook 事件投递平台</span><h1>让每一次投递，<br />都有清晰的回应。</h1><p>从事件提交到失败排查，<br />在一个工作台里看清完整路径。</p><div className="login-route"><span><FileTextOutlined />事件提交</span><i /><span><ReloadOutlined />投递与重试</span><i /><span><ApiOutlined />接收端</span></div><div className="login-example"><div><span className="mono">evt-demo-1031</span><StatusTag status="SUCCESS" /></div><p>第 2 轮 · 接收端已确认接收</p><small>虚构展示样例 · 非实时投递</small></div></div><footer>个人演示项目 · 不接入真实个人或支付数据</footer></section><section className="login-form-area"><div className="login-form-wrap"><span className="outline-tag">静态展示</span><h2>登录工作台</h2><p>登录表单预览，尚未接入真实认证。</p><StaticNotice>未接入后端。请勿输入真实密码；此页不会发送、保存或验证凭据。</StaticNotice><Form layout="vertical" autoComplete="on" className="login-form">
    <Form.Item label="账号" name="username" rules={[{ validator: validateUsername }]}><Input autoComplete="username" placeholder="输入账号" /></Form.Item><Form.Item label="密码" name="password" rules={[{ validator: validatePassword }]}><Input type={passwordVisible ? 'text' : 'password'} autoComplete="current-password" placeholder="输入密码" suffix={
      // 按钮事件仅切换可见性，aria-pressed 告知辅助技术当前显示状态。
      <Button type="text" size="small" aria-label={passwordVisible ? '隐藏密码' : '显示密码'} aria-pressed={passwordVisible} onClick={togglePassword}>{passwordVisible ? '隐藏' : '显示'}</Button>
    } /></Form.Item><Button className="login-submit" type="primary" disabled block>登录 · 待接口接入</Button>
    </Form><p className="subtle">静态阶段全部页面均可直接访问，不具备访问控制。</p><Link className="table-link" to="/events">浏览静态页面（非登录成功）</Link></div><footer>不创建 Token，不伪造登录或退出成功</footer></section></div>;
}
