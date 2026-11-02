import { lazy, Suspense, useEffect } from 'react';
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import type { Location } from 'react-router-dom';
import { WorkspaceLayout } from '../layouts/WorkspaceLayout';
import { LoadingState, NotFound } from '../shared/ui';
import { useSession } from '../shared/query';
import { endSession } from '../shared/api/session';

// 页面按路由懒加载：打开登录页时，不必先下载所有管理页面代码。
const EventsPage = lazy(() => import('../features/events/EventsPage').then((module) => ({ default: module.EventsPage })));
const EventDetailPage = lazy(() => import('../features/events/EventDetailPage').then((module) => ({ default: module.EventDetailPage })));
const EndpointsPage = lazy(() => import('../features/endpoints/EndpointsPage').then((module) => ({ default: module.EndpointsPage })));
const OverviewPage = lazy(() => import('../features/overview/OverviewPage').then((module) => ({ default: module.OverviewPage })));
const SimulatorPage = lazy(() => import('../features/simulator/SimulatorPage').then((module) => ({ default: module.SimulatorPage })));
const AuditPage = lazy(() => import('../features/audit/AuditPage').then((module) => ({ default: module.AuditPage })));
const LoginPage = lazy(() => import('../features/auth/LoginPage').then((module) => ({ default: module.LoginPage })));

// 受保护页面的统一入口：会话模块已恢复缓存，没有有效会话才去登录页。
function RequireSession() {
  const session = useSession();
  const location = useLocation();

  useEffect(() => {
    if (!session) return;
    // 服务端给出的 expiresAt 是最终依据，前端定时器只负责及时切换到登录页。
    const timer = setTimeout(
      () => endSession(session.generation),
      Math.max(0, Date.parse(session.expiresAt) - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [session]);

  if (!session || Date.parse(session.expiresAt) <= Date.now()) {
    // 保存用户原本想去的站内路径，登录成功后再安全返回。
    return <Navigate to="/login" replace state={{ returnTo: `${location.pathname}${location.search}`, expired: Boolean(session) }} />;
  }
  return <Outlet />;
}

export function AppRoutes() {
  const session = useSession();
  const location = useLocation();
  const state = location.state as { background?: Location } | null;

  // 从事件列表打开详情时，列表作为背景保留，详情使用 Drawer 展示。
  // 直接刷新详情 URL 时没有 background，因此会展示独立详情页。
  const background = session && state?.background?.pathname === '/events' && location.pathname.startsWith('/events/')
    ? state.background
    : undefined;

  return <Suspense fallback={<LoadingState />}>
    <Routes location={background ?? location}>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireSession />}>
        <Route element={<WorkspaceLayout />}>
          <Route path="/" element={<Navigate to="/events" replace />} />
          <Route path="/events" element={<EventsPage />} />
          <Route path="/events/:eventId" element={<EventDetailPage />} />
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/endpoints" element={<EndpointsPage />} />
          <Route path="/simulator" element={<SimulatorPage />} />
          <Route path="/audit-logs" element={<AuditPage />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>

    {/* 第二套路由只在存在 background 时渲染 Drawer，不影响浏览器地址。 */}
    {background && <Routes><Route element={<RequireSession />}><Route path="/events/:eventId" element={<EventDetailPage drawer />} /></Route></Routes>}
  </Suspense>;
}
