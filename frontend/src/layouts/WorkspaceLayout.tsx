import { useState } from 'react';
import { Button, Modal } from 'antd';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { AppstoreOutlined, ApiOutlined, ExperimentOutlined, FileTextOutlined, LinkOutlined, LogoutOutlined, ProfileOutlined } from '@ant-design/icons';
import { useSession, useWrite } from '../shared/query';
import { endSession } from '../shared/api/session';
import { api } from '../shared/api/resources';
import { ErrorNotice } from '../shared/ui';
const navigation = [
  { path: '/overview', title: '概览', icon: <AppstoreOutlined /> },
  { path: '/events', title: '事件管理', icon: <FileTextOutlined /> },
  { path: '/endpoints', title: '接收端管理', icon: <ApiOutlined /> },
  { path: '/simulator', title: '故障模拟器', icon: <ExperimentOutlined /> },
  { path: '/audit-logs', title: '操作记录', icon: <ProfileOutlined /> },
];
export function Brand() { return <Link className="brand" to="/events" aria-label="HookFlow 事件管理"><span className="brand-symbol" aria-hidden="true"><i /><i /><i /></span><span>HookFlow<small>事件投递工作台</small></span></Link>; }
export function WorkspaceLayout() {
  // 顶部用户区需要实时显示当前内存会话中的用户名。
  const session = useSession();
  const [logoutOpen, setLogoutOpen] = useState(false);
  // 退出是写操作：不自动重试，成功后才清理本地会话。
  const logout = useWrite({ mutationFn: api.logout, onSuccess: () => endSession() }, []);
  const location = useLocation();
  const title = navigation.find((item) => location.pathname.startsWith(item.path))?.title ?? '工作台';
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  // logoutOpen 只控制确认弹窗；真正退出由用户确认后才调用 API。
  return <><a className="skip-link" href="#main">跳转到主要内容</a><aside className="sidebar" aria-label="工作台导航"><Brand /><div className="nav-group-label">工作空间</div><nav>{navigation.map((item) => <NavLink key={item.path} to={item.path} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} aria-label={item.title} title={item.title}>{item.icon}<span className="nav-text">{item.title}</span></NavLink>)}</nav><div className="sidebar-note"><LinkOutlined /><strong>每一次投递，都有迹可循。</strong><p>从事件到响应，保留完整的投递路径。</p><span className="outline-tag">{import.meta.env.VITE_APP_ENV === 'pro' ? '管理环境' : '开发环境'}</span></div><div className="user-block"><div className="avatar">{session?.username.slice(0, 1)}</div><div><strong>{session?.username}</strong><small>当前登录账号</small></div></div></aside><div className="workspace"><header className="topbar"><div className="breadcrumb">工作台<span>/</span><strong>{title}</strong></div><div className="topbar-right"><span>{import.meta.env.VITE_APP_ENV === 'pro' ? 'pro' : 'dev'}</span><Button type="text" icon={<LogoutOutlined />} onClick={() => { logout.reset(); setLogoutOpen(true); }}>退出</Button></div></header><main id="main"><Outlet /><footer className="page-footer"><span>HookFlow · 事件投递工作台</span><span>本地时区：{timezone}</span></footer></main></div><Modal title="退出工作台？" open={logoutOpen} onCancel={() => { if (!logout.isPending) setLogoutOpen(false); }} maskClosable={false} closable={!logout.isPending} keyboard={!logout.isPending} footer={<div className="modal-actions"><Button disabled={logout.isPending} onClick={() => setLogoutOpen(false)}>取消</Button>{logout.error && <Button onClick={() => endSession()}>仅退出本地</Button>}<Button type="primary" loading={logout.isPending} onClick={() => logout.submit(undefined)}>{logout.error ? '重试撤销' : '退出并撤销会话'}</Button></div>}><p>退出将请求服务器撤销当前管理员会话。</p><ErrorNotice error={logout.error} />{logout.error && <p>尚未确认服务器撤销。仅退出本地会清除浏览器会话，但原 Token 可能持续有效至到期。</p>}</Modal></>;
}
