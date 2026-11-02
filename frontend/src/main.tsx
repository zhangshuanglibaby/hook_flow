import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { AppRoutes } from './app/AppRoutes';
import { ErrorBoundary } from './app/ErrorBoundary';
import { theme, tokens } from './app/theme';
import { queryClient } from './shared/query';
import 'antd/dist/reset.css';
import './app/styles.css';

// 将单一主题令牌写入 CSS 变量，普通 CSS 和 Ant Design 使用同一套颜色。
for (const [name, value] of Object.entries(tokens)) document.documentElement.style.setProperty(`--${name}`, value);
// 入口 HTML 必须提供 root，否则直接抛出明确错误，不显示空白页面。
const container = document.getElementById('root');
if (!container) throw new Error('未找到 HookFlow 页面挂载节点。');

// 组件树从外到内依次提供：Ant Design 主题、错误兜底、Query 缓存和浏览器路由。
// StrictMode 只在开发阶段帮助发现副作用问题，不改变生产业务逻辑。
createRoot(container).render(<StrictMode><ConfigProvider locale={zhCN} theme={theme}><App><ErrorBoundary><QueryClientProvider client={queryClient}><BrowserRouter><AppRoutes /></BrowserRouter></QueryClientProvider></ErrorBoundary></App></ConfigProvider></StrictMode>);
