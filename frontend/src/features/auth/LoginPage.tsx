// 登录页面只允许输入及密码可见切换，不比较固定凭据、不生成 Token。
import { useState } from 'react';
import { Form, Input, Button } from 'antd';
import { Link } from 'react-router-dom';
// 导入左侧背景图片，由 Vite 处理构建后的资源地址，不使用网站根目录绝对路径。
import loginBackground from '../../assets/images/bg.png';
import { StaticNotice } from '../../shared/ui';
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
  // 保留双栏布局；左侧图片已含品牌和插画，不再叠加原来的文字与卡片。
  return <div className="login-layout">
    {/* 使用导入地址设置背景；辅助文字为读屏提供说明，窄屏仍隐藏左侧。 */}
    <section className="login-story" aria-label="HookFlow 事件投递平台介绍" style={{ backgroundImage: `url("${loginBackground}")` }}>
      <p className="sr-only">HookFlow 事件投递工作台：连接业务系统与 HTTP 接收端，追踪投递记录和失败重试。图片中的状态与记录仅为示意。</p>
    </section><section className="login-form-area"><div className="login-form-wrap"><span className="outline-tag">静态展示</span><h2>登录工作台</h2><p>登录表单预览，尚未接入真实认证。</p><StaticNotice>未接入后端。请勿输入真实密码；此页不会发送、保存或验证凭据。</StaticNotice><Form layout="vertical" autoComplete="on" className="login-form">
    <Form.Item label="账号" name="username" rules={[{ validator: validateUsername }]}><Input autoComplete="username" placeholder="输入账号" /></Form.Item><Form.Item label="密码" name="password" rules={[{ validator: validatePassword }]}><Input type={passwordVisible ? 'text' : 'password'} autoComplete="current-password" placeholder="输入密码" suffix={
      // 按钮事件仅切换可见性，aria-pressed 告知辅助技术当前显示状态。
      <Button type="text" size="small" aria-label={passwordVisible ? '隐藏密码' : '显示密码'} aria-pressed={passwordVisible} onClick={togglePassword}>{passwordVisible ? '隐藏' : '显示'}</Button>
    } /></Form.Item><Button className="login-submit" type="primary" disabled block>登录 · 待接口接入</Button>
    </Form><p className="subtle">静态阶段全部页面均可直接访问，不具备访问控制。</p><Link className="table-link" to="/events">浏览静态页面（非登录成功）</Link></div><footer>不创建 Token，不伪造登录或退出成功</footer></section></div>;
}
