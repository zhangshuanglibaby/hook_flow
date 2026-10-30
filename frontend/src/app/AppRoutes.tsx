// 使用 React Router 的背景位置模式，使列表详情保持独立 URL 与侧面板两种入口。
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { Location } from 'react-router-dom';
import { WorkspaceLayout } from '../layouts/WorkspaceLayout';
import { EventsPage } from '../features/events/EventsPage';
import { EventDetailPage } from '../features/events/EventDetailPage';
import { EndpointsPage } from '../features/endpoints/EndpointsPage';
import { OverviewPage } from '../features/overview/OverviewPage';
import { SimulatorPage } from '../features/simulator/SimulatorPage';
import { AuditPage } from '../features/audit/AuditPage';
import { LoginPage } from '../features/auth/LoginPage';
import { NotFound } from '../shared/ui';
// 开发专用状态入口在生产不注册，且不出现在工作台导航中。
import { StatePreviewPage } from '../dev/StatePreviewPage';

// 全部页面在静态阶段公开，不设置虚假的登录守卫。
export function AppRoutes() {
  // 读取实际地址，浏览器前进后退会自然改变页面或详情面板。
  const location = useLocation();
  // state 由列表链接写入；直接打开详情没有背景位置。
  const state = location.state as { background?: Location } | null;
  // 只允许本站事件列表作为背景，不使用任意外部路径。
  const background = state?.background?.pathname === '/events' && location.pathname.startsWith('/events/') ? state.background : undefined;
  // 背景存在时主 Routes 继续显示列表，详情由第二组 Routes 覆盖在侧面板上。
  return <><Routes location={background ?? location}>
    {/* 登录页使用独立双栏布局，不显示管理侧栏。 */}
    <Route path="/login" element={<LoginPage />} />
    {/* 共用工作台布局包裹其余页面，不检查认证。 */}
    <Route element={<WorkspaceLayout />}>
      {/* 默认入口沿用已确认的事件管理，不根据假登录跳转。 */}
      <Route path="/" element={<Navigate to="/events" replace />} />
      <Route path="/events" element={<EventsPage />} />
      {/* 无背景位置时详情独立显示，刷新不依赖前一页数据。 */}
      <Route path="/events/:eventId" element={<EventDetailPage />} />
      <Route path="/overview" element={<OverviewPage />} />
      <Route path="/endpoints" element={<EndpointsPage />} />
      <Route path="/simulator" element={<SimulatorPage />} />
      <Route path="/audit-logs" element={<AuditPage />} />
      {/* 只有开发模式能打开状态画廊，生产会落到 404。 */}
      {import.meta.env.DEV && <Route path="/__preview/states" element={<StatePreviewPage />} />}
      {/* 未匹配地址与数据空态分开处理。 */}
      <Route path="*" element={<NotFound />} />
    </Route>
  </Routes>
    {/* 只在从列表打开详情时挂载 Drawer，URL 依然是可直达详情地址。 */}
    {background && <Routes><Route path="/events/:eventId" element={<EventDetailPage drawer />} /></Routes>}
  </>;
}
