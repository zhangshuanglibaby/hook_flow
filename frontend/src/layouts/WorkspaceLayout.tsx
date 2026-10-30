// 退出入口只打开确认提示，不清理或伪造任何认证状态。
import { useState } from 'react';
import { Button } from 'antd';
// 使用路由控件保持页面导航与浏览器前进、后退一致。
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { AppstoreOutlined, ApiOutlined, ExperimentOutlined, FileTextOutlined, LinkOutlined, LogoutOutlined, ProfileOutlined } from '@ant-design/icons';
import { PreviewModal } from '../shared/ui';

// 导航集中定义，不加入原型以外的业务页面。
const navigation = [
  { path: '/overview', title: '概览', icon: <AppstoreOutlined /> },
  { path: '/events', title: '事件管理', icon: <FileTextOutlined /> },
  { path: '/endpoints', title: '接收端管理', icon: <ApiOutlined /> },
  { path: '/simulator', title: '故障模拟器', icon: <ExperimentOutlined /> },
  { path: '/audit-logs', title: '操作记录', icon: <ProfileOutlined /> },
];
// 节点品牌图形沿用原型，不请求外部图像或字体资源。
export function Brand() {
  // 链接回事件管理，维持已确定的默认入口。
  return <Link className="brand" to="/events" aria-label="HookFlow 事件管理"><span className="brand-symbol" aria-hidden="true"><i /><i /><i /></span><span>HookFlow<small>事件投递工作台</small></span></Link>;
}
// 工作台不设置认证守卫，所有页面在静态阶段均可直接访问。
export function WorkspaceLayout() {
  // 开关仅影响退出确认浮层，不代表已有登录身份。
  const [logoutOpen, setLogoutOpen] = useState(false);
  // 关闭确认不撤销凭据；本阶段根本没有创建凭据。
  function closeLogout() {
    // 将确认浮层隐藏，焦点返回退出入口。
    setLogoutOpen(false);
  }
  // 读取路径用于面包屑，详情也归属事件管理。
  const location = useLocation();
  // 匹配一级页面标题，不从浏览器伪造用户身份。
  const title = navigation.find((item) => location.pathname.startsWith(item.path))?.title ?? '页面预览';
  // 本地时区名称仅用于时间展示说明，不影响样例数据。
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  // 页面由 Outlet 渲染，公共导航在切换时保持相同结构。
  return <><a className="skip-link" href="#main">跳转到主要内容</a><aside className="sidebar" aria-label="工作台导航"><Brand /><div className="nav-group-label">工作空间</div><nav>
    {/* 遍历固定导航并生成真实链接；NavLink 自动设置 aria-current。 */}
    {navigation.map((item) => <NavLink key={item.path} to={item.path} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} aria-label={item.title} title={item.title}>{item.icon}<span className="nav-text">{item.title}</span></NavLink>)}
  </nav><div className="sidebar-note"><LinkOutlined /><strong>每一次投递，都有迹可循。</strong><p>从事件到响应，保留完整的投递路径。</p><span className="outline-tag">静态页面实现阶段</span></div><div className="user-block"><div className="avatar">H</div><div><strong>未接入认证</strong><small>当前无登录身份</small></div>
    {/* 退出入口只打开说明，不切换页面冒充会话已撤销。 */}
    <Button type="text" icon={<LogoutOutlined />} aria-label="退出工作台说明" onClick={() => setLogoutOpen(true)} />
  </div></aside>
  <div className="workspace"><header className="topbar"><div className="breadcrumb">工作台<span>/</span><strong>{title}</strong></div><div className="topbar-right"><span>未接入后端</span><Link to="/login">登录页预览</Link></div></header><main id="main"><Outlet /><footer className="page-footer"><span>HookFlow · 静态展示 / 未接入后端</span><span>本地时区：{timezone}</span></footer></main></div>
    {/* 最终退出按钮禁用，不显示虚假的“退出成功”。 */}
    <PreviewModal title="退出工作台？" open={logoutOpen} onClose={closeLogout} action="退出"><p>本阶段未接入认证，没有真实会话可撤销。正式退出接口接入前，此操作保持禁用。</p></PreviewModal>
  </>;
}
