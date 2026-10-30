// 引入 React 严格模式，帮助开发阶段发现不安全的副作用。
import { StrictMode } from 'react';
// React DOM 将组件树挂载到入口 HTML 的 root 节点。
import { createRoot } from 'react-dom/client';
// 全局设置 Ant Design 中文与冷蓝主题，避免每页单独覆盖。
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
// BrowserRouter 使用真实路径，支持前进、后退与直接访问详情。
import { BrowserRouter } from 'react-router-dom';
import { AppRoutes } from './app/AppRoutes';
import { theme, tokens } from './app/theme';
import 'antd/dist/reset.css';
import './app/styles.css';

// 将唯一主题令牌转换成 CSS 自定义属性，CSS 不重复维护品牌颜色。
for (const [name, value] of Object.entries(tokens)) {
  // 每个令牌写入根节点，使布局、按钮和浮层使用同一主题。
  document.documentElement.style.setProperty(`--${name}`, value);
}
// 查找入口节点，避免对不存在的挂载目标强制断言。
const container = document.getElementById('root');
// 入口配置缺失时给明确异常，而不是静默展示空白页面。
if (!container) throw new Error('未找到 HookFlow 页面挂载节点。');
// 挂载整个应用；不创建 API 客户端、Token 或模拟接口层。
createRoot(container).render(
  <StrictMode><ConfigProvider locale={zhCN} theme={theme}><BrowserRouter><AppRoutes /></BrowserRouter></ConfigProvider></StrictMode>,
);
