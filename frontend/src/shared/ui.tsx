// 复用 Ant Design 控件与线性图标，不自行搭建复杂组件系统。
import { Alert, Button, Empty, Modal, Pagination, Skeleton, Table } from 'antd';
import { CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, InboxOutlined, LoadingOutlined, PauseCircleOutlined, SyncOutlined } from '@ant-design/icons';
// 表格列类型只用于编译期检查。
import type { TableColumnsType } from 'antd';
import type { ReactNode } from 'react';
// 页面标题与返回使用真正的路由链接。
import { Link } from 'react-router-dom';
import { statusLabels } from '../data/labels';
import type { DeliveryStatus } from '../data/types';

// 状态图标与中文共同表示含义，不依赖颜色识别状态。
const statusIcons: Record<DeliveryStatus, ReactNode> = {
  PENDING: <ClockCircleOutlined />, DELIVERING: <LoadingOutlined />, WAIT_RETRY: <SyncOutlined />,
  PAUSED: <PauseCircleOutlined />, SUCCESS: <CheckCircleOutlined />, FINAL_FAILED: <CloseCircleOutlined />,
};
// 统一渲染六种任务状态，未知值保留原文而不猜测为成功。
export function StatusTag({ status, text }: { status: string; text?: string }) {
  // 接收端可传入“已启用”等文案；任务仍从名称表取值，未知枚举保守降级。
  const label = text ?? statusLabels[status as DeliveryStatus] ?? `未知状态：${status}`;
  // 已知图标为空时显示文字，样式仍保持中性。
  return <span className={`status-tag status-${status}`} >{statusIcons[status as DeliveryStatus]}{label}</span>;
}
// 复用原型标题、简短说明和右侧主要操作。
export function PageHeading({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  // action 是调用页面传入的操作；不存在时不额外占位。
  return <div className="page-heading"><div><div className="heading-line"><h1 tabIndex={-1}>{title}</h1><span className="outline-tag">静态展示</span></div><p>{description}</p></div>{action}</div>;
}
// 将本轮边界放在可见位置，不让用户误认为已经连通后端。
export function StaticNotice({ children = '静态展示 / 未接入后端。页面使用只读本地样例，不执行登录、投递或业务写入。' }: { children?: ReactNode }) {
  // 使用标准信息提示，图标和文字同时可见。
  return <Alert className="static-notice" type="info" showIcon message={children} />;
}
// 所有写操作的最终按钮都禁用，并有可见的待接入说明。
export function PendingAction({ children }: { children: ReactNode }) {
  // 明确在按钮文字中包含待接入说明，触屏用户也能看见。
  return <Button type="primary" disabled>{children} · 待接口接入</Button>;
}
// 表单和确认共用同一种弹窗，保留组件库的焦点约束、Escape 和关闭后焦点返回。
export function PreviewModal({ title, open, onClose, children, action = '确认' }: { title: string; open: boolean; onClose: () => void; children: ReactNode; action?: string }) {
  // onCancel 只关闭浮层，不提交或改写任何静态数据。
  return <Modal title={title} open={open} onCancel={onClose} keyboard maskClosable={false} focusTriggerAfterClose width={520} footer={<div className="modal-actions"><Button onClick={onClose}>取消</Button><PendingAction>{action}</PendingAction></div>}>
    <div className="modal-content"><StaticNotice />{children}</div>
  </Modal>;
}
// 轻量表格包装集中处理局部横向滚动和本地样例分页；这不是服务端分页。
export function SampleTable<T extends { readonly id: string | number }>({ rows, columns, label, page, size, onPage }: { rows: readonly T[]; columns: TableColumnsType<T>; label: string; page: number; size: number; onPage: (page: number, size: number) => void }) {
  // 根据当前页切片展示样例，不修改原始数组。
  const visibleRows = rows.slice((page - 1) * size, page * size);
  // 局部容器可聚焦，键盘用户可以横向查看宽表格。
  return <><div className="table-region" role="region" aria-label={`${label}，可横向滚动`} tabIndex={0}>
    <Table<T> rowKey="id" columns={columns} dataSource={visibleRows} pagination={false} scroll={{ x: 850 }} />
  </div><footer className="pagination"><span>共 {rows.length} 条本地展示样例</span>
    {/* 翻页事件只改变展示页码；每页数量变化时由页面回到第一页。 */}
    <Pagination current={page} pageSize={size} total={rows.length} showSizeChanger pageSizeOptions={[5, 10, 20]} onChange={onPage} locale={{ items_per_page: '条 / 页' }} />
  </footer></>;
}
// 分别提供加载、空数据、无匹配和错误展示，独立开发入口复用这些组件。
export function DisplayState({ kind }: { kind: 'loading' | 'empty' | 'no-match' | 'error' }) {
  // 加载不显示 0 条，避免把加载过程当成已经查到空数据。
  if (kind === 'loading') return <section className="display-state" role="status" aria-busy="true"><p>正在加载…（仅样式预览，不发送请求）</p><Skeleton active paragraph={{ rows: 5 }} /></section>;
  // 错误不是空态，并且明确这不是实际发生的网络错误。
  if (kind === 'error') return <section className="display-state"><Alert type="error" showIcon message="暂时无法加载内容" description="这是错误样式预览。未执行真实请求，也不会返回伪造响应。" /><Button disabled>重新加载 · 待接口接入</Button></section>;
  // 无匹配与普通空数据使用不同引导文案。
  const noMatch = kind === 'no-match';
  // 空态不把创建或清除按钮伪装成已经可用的业务功能。
  return <section className="display-state"><Empty image={<InboxOutlined className="empty-icon" />} description={<><h3>{noMatch ? '没有符合条件的事件' : '暂无数据'}</h3><p>{noMatch ? '请调整事件 ID、状态或创建时间；正式查询待接口接入。' : '后续接入真实后端后，将在这里展示查询结果。'}</p></>} /></section>;
}
// 前端路径未匹配与详情 ID 不存在分别使用明确文案。
export function NotFound({ detail = false }: { detail?: boolean }) {
  // 返回链接总是站内事件页，不盲目后退到站外页面。
  return <div className="not-found"><span className="error-number">404</span><h1>{detail ? '没有这条展示样例' : '页面不存在'}</h1><p>{detail ? '当前仅提供文档中列出的本地事件样例，尚未查询后端。' : '请检查地址，或返回事件管理。'}</p><Link className="action-link" to="/events">返回事件管理</Link></div>;
}
