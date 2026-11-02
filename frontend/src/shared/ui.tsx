import { Alert, Button, Empty, Pagination, Skeleton, Table } from 'antd';
import { CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, InboxOutlined, InfoCircleOutlined, LoadingOutlined, PauseCircleOutlined, SyncOutlined, WarningOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { statusLabels } from '../data/labels';
import type { DeliveryStatus } from '../data/types';
import type { Page } from './api/schemas';
import { ApiError } from './api/client';

// 状态图标只作为辅助，真正含义始终通过中文文字显示。
const statusIcons: Record<DeliveryStatus, ReactNode> = {
  PENDING: <ClockCircleOutlined />, DELIVERING: <LoadingOutlined />, WAIT_RETRY: <SyncOutlined />,
  PAUSED: <PauseCircleOutlined />, SUCCESS: <CheckCircleOutlined />, FINAL_FAILED: <CloseCircleOutlined />,
};

// 统一渲染任务状态。后端未来增加未知枚举时，前端不会误判成成功。
export function StatusTag({ status, text }: { status: string; text?: string }) {
  const known = Object.hasOwn(statusLabels, status);
  return <span className={`status-tag status-${known ? status : 'unknown'}`}>
    {known ? statusIcons[status as DeliveryStatus] : null}
    {text ?? (known ? statusLabels[status as DeliveryStatus] : `未知状态：${status}`)}
  </span>;
}

// 所有管理页使用统一的标题区域，action 由页面传入右侧按钮。
export function PageHeading({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <div className="page-heading"><div><h1 tabIndex={-1}>{title}</h1><p>{description}</p></div>{action}</div>;
}

// 信息类提示统一使用空心图标；警告和错误仍使用各自的语义样式。
export function InfoNotice({ message, description, action }: { message: ReactNode; description?: ReactNode; action?: ReactNode }) {
  return <Alert type="info" showIcon icon={<InfoCircleOutlined />} message={message} description={description} action={action} />;
}

// 警告统一使用空心三角图标，避免各页面退回组件库默认的实心感叹号。
export function WarningNotice({ message, description, action }: { message: ReactNode; description?: ReactNode; action?: ReactNode }) {
  return <Alert type="warning" showIcon icon={<WarningOutlined />} message={message} description={description} action={action} />;
}

// 展示真实请求错误，并区分“普通失败”“旧数据可能过期”和“写入结果不确定”。
export function ErrorNotice({ error, retry, stale = false }: { error: Error | null; retry?: () => void; stale?: boolean }) {
  if (!error) return null;
  const api = error instanceof ApiError ? error : null;
  return <Alert className="error-notice" type="error" showIcon message={api?.uncertain ? '写入结果尚未确认，请先查询记录' : stale ? '刷新失败，当前显示的数据可能已过期' : '请求未完成'} description={<><p>{error.message}</p>{api?.requestId && <p className="mono">请求 ID：{api.requestId}</p>}{api?.httpStatus === 403 && <p>当前凭据没有该操作权限，会话未被清除。</p>}{retry && <Button onClick={retry}>重新加载</Button>}</>} />;
}

// 首次加载使用独立状态，不把“正在加载”误显示为“暂无数据”。
export function LoadingState() { return <section className="display-state" role="status" aria-busy="true"><p>正在加载…</p><Skeleton active paragraph={{ rows: 4 }} /></section>; }

// 正常空数据和筛选后无匹配数据可以传入不同的引导动作。
export function EmptyState({ filtered = false, action }: { filtered?: boolean; action?: ReactNode }) {
  return <div className="display-state"><Empty image={<InboxOutlined className="empty-icon" />} description={filtered ? '没有符合条件的记录，请调整或清除筛选。' : '暂无记录。'}>{action}</Empty></div>;
}

// 所有服务端分页表格共用这个包装：数据由后端提供，分页按钮只改变查询参数。
export function ServerTable<T extends object>({ data, columns, label, rowKey = 'id', onPage, loading = false, emptyAction }: { data: Page<T>; columns: TableColumnsType<T>; label: string; rowKey?: string; onPage: (page: number, size: number) => void; loading?: boolean; emptyAction?: ReactNode }) {
  return <><div className="table-region" role="region" aria-label={`${label}，可横向滚动`} tabIndex={0} aria-busy={loading}>
    <Table<T> rowKey={rowKey} columns={columns} dataSource={data.items} pagination={false} loading={loading} scroll={{ x: 850 }} locale={{ emptyText: <EmptyState action={emptyAction} /> }} />
  </div><footer className="pagination"><span>共 {data.total.toLocaleString('zh-CN')} 条记录{loading ? ' · 更新中' : ''}</span><Pagination current={data.page} pageSize={data.size} total={data.total} showSizeChanger pageSizeOptions={[20, 50, 100]} onChange={(page, size) => onPage(size !== data.size ? 1 : page, size)} locale={{ items_per_page: '条 / 页' }} /></footer></>;
}

// 路由不存在或详情返回404时使用站内返回链接，不盲目返回外部历史页面。
export function NotFound({ detail = false }: { detail?: boolean }) {
  return <div className="not-found"><span className="error-number">404</span><h1>{detail ? '事件不存在' : '页面不存在'}</h1><p>{detail ? '记录可能已清理，请检查事件 ID。' : '请检查地址，或返回事件管理。'}</p><Link className="action-link" to="/events">返回事件管理</Link></div>;
}
