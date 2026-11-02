import { useState } from 'react';
import { Alert, Button, ConfigProvider, Descriptions, Drawer, Empty, Form, Input, Pagination, Select, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { theme } from '../app/theme';
import { filterSnapshot, snapshotEndpoints, snapshotEvents, snapshotLabels, snapshotPage, snapshotStatuses } from './data';
import type { SnapshotEvent, SnapshotFilters, SnapshotStatus } from './data';

const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const format = (value: string) => timeFormat.format(new Date(value));
function State({ status }: { status: SnapshotStatus }) {
  return <span className={`snapshot-state state-${status}`}><span aria-hidden="true">●</span>{snapshotLabels[status]}</span>;
}
type Draft = Omit<SnapshotFilters, 'from' | 'to'> & { from?: string; to?: string };

export function SnapshotPage() {
  const [form] = Form.useForm<Draft>();
  const [filters, setFilters] = useState<SnapshotFilters>({});
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<SnapshotEvent | null>(null);
  const [error, setError] = useState('');
  const [refreshed, setRefreshed] = useState(false);
  const filtered = filterSnapshot(snapshotEvents, filters);
  function reset() { form.resetFields(); setFilters({}); setPage(1); setError(''); }
  function apply(draft: Draft) {
    const from = draft.from ? new Date(`${draft.from}T00:00:00+08:00`).toISOString() : undefined;
    const to = draft.to ? new Date(`${draft.to}T00:00:00+08:00`).toISOString() : undefined;
    if (from && to && from >= to) { setError('结束日期必须晚于开始日期；结束日不包含。'); return; }
    setFilters({ ...draft, from, to }); setPage(1); setError(''); setRefreshed(false);
  }
  const columns: TableColumnsType<SnapshotEvent> = [
    { title: '事件 ID / 类型', width: 240, render: (_, event) => <><button className="snapshot-id" onClick={() => setSelected(event)}>{event.eventId}</button><span className="snapshot-secondary">{event.eventType}</span></> },
    { title: '接收端', width: 175, render: (_, event) => <><strong className="snapshot-endpoint">{event.endpointName}</strong><span className="snapshot-secondary">模拟接收端 · 展示专用</span></> },
    { title: '投递状态', width: 135, render: (_, event) => <State status={event.status} /> },
    { title: '轮次 / 尝试', width: 135, render: (_, event) => <>第 1 轮<span className="snapshot-secondary">{event.attempts.length} / 3 次尝试</span></> },
    { title: '创建时间', width: 180, render: (_, event) => <span className="snapshot-time">{format(event.createdAt)}</span> },
    { title: '最近投递时间', width: 180, render: (_, event) => event.attempts.length ? <span className="snapshot-time">{format(event.attempts.at(-1)!.startedAt)}</span> : <span className="snapshot-secondary">—<br />尚未开始</span> },
    { title: '操作', width: 105, render: (_, event) => <Button type="link" onClick={() => setSelected(event)}>查看详情</Button> },
  ];
  return <ConfigProvider locale={zhCN} theme={theme}>
    <a href="#snapshot-content" className="snapshot-skip">跳到事件列表</a>
    <header className="snapshot-topbar"><div>工作台 <span>/</span> <strong>事件管理</strong></div><span className="snapshot-environment">独立展示快照 · 不连接数据库</span></header>
    <main id="snapshot-content" className="snapshot-main">
      <div className="snapshot-heading"><div><div className="snapshot-title"><h1>事件管理</h1><span className="snapshot-badge">30 条演示数据</span></div><p>五种状态各 6 条，固定展示业务投递的不同阶段。</p></div><Button icon={<ReloadOutlined />} onClick={() => { reset(); setRefreshed(true); }}>重新载入快照</Button></div>
      <Alert type="info" showIcon message="只读展示环境" description="接收端和事件均为虚构快照。状态不会自动变化，不发送真实请求，也不影响现有联调页面、开发数据库或已有事件。" className="snapshot-notice" />
      <section className="snapshot-panel" aria-label="事件管理展示快照">
        <nav className="snapshot-tabs" aria-label="快捷状态筛选">
          <Button type="text" className={!filters.status ? 'is-selected' : ''} onClick={() => { form.setFieldValue('status', undefined); setFilters({ ...filters, status: undefined }); setPage(1); }}>全部事件</Button>
          {snapshotStatuses.map((status) => <Button type="text" key={status} className={filters.status === status ? 'is-selected' : ''} onClick={() => { form.setFieldValue('status', status); setFilters({ ...filters, status }); setPage(1); }}>{snapshotLabels[status]} <span className="snapshot-tab-count">6</span></Button>)}
        </nav>
        <Form form={form} layout="vertical" onFinish={apply} className="snapshot-filters">
          <div className="snapshot-filter-grid"><Form.Item label="事件 ID" name="eventId"><Input prefix={<SearchOutlined />} placeholder="输入完整事件 ID" /></Form.Item><Form.Item label="事件类型" name="eventType"><Input placeholder="如 order.paid" /></Form.Item><Form.Item label="接收端" name="endpointId"><Select allowClear placeholder="全部接收端" options={snapshotEndpoints.map((endpoint) => ({ label: endpoint.name, value: endpoint.id }))} /></Form.Item><Form.Item label="投递状态" name="status"><Select allowClear placeholder="全部状态" options={snapshotStatuses.map((status) => ({ label: snapshotLabels[status], value: status }))} /></Form.Item></div>
          <div className="snapshot-filter-bottom"><div className="snapshot-date"><Form.Item label="创建时间起始日" name="from"><Input type="date" aria-label="创建时间起始日" /></Form.Item><span>至</span><Form.Item label="创建时间结束日" name="to"><Input type="date" aria-label="创建时间结束日" /></Form.Item><span>北京时间 · 结束日不包含</span></div><div className="snapshot-actions"><Button onClick={reset}>重置</Button><Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button></div></div>
        </Form>
        {error && <Alert type="error" showIcon message={error} />}
        <div className="snapshot-caption"><strong>事件列表 <span>{filtered.length} 条</span></strong><span role="status">{refreshed ? '已恢复固定快照，未发送网络请求' : '状态与尝试均为固定展示值'}</span></div>
        <div role="region" aria-label="事件列表，可横向滚动" tabIndex={0} className="snapshot-table"><Table rowKey="eventId" columns={columns} dataSource={snapshotPage(filtered, page, size)} pagination={false} scroll={{ x: 1150 }} locale={{ emptyText: <Empty description="没有符合条件的演示记录"><Button onClick={reset}>清除筛选</Button></Empty> }} /></div>
        <footer className="snapshot-pagination"><span>{filtered.length ? `显示第 ${(page - 1) * size + 1}–${Math.min(page * size, filtered.length)} 条，共 ${filtered.length} 条` : '共 0 条'}</span><Pagination current={page} pageSize={size} total={filtered.length} showSizeChanger pageSizeOptions={[10, 20, 30]} onChange={(next, nextSize) => { setPage(nextSize !== size ? 1 : next); setSize(nextSize); }} /></footer>
      </section>
      <p className="snapshot-footnote">成功仅表示接收端确认接收；最终失败表示未确认成功。此页为冻结的展示样例，不代表真实后端执行结果。</p>
      <footer className="snapshot-footer"><span>HookFlow · 独立事件展示</span><span>30 条记录 · 5 个具名接收端 · Asia/Shanghai</span></footer>
    </main>
    <Drawer title="演示事件详情" open={Boolean(selected)} onClose={() => setSelected(null)} width={620} rootClassName="snapshot-drawer">
      {selected && <><Alert type="info" message="固定展示样例，不来自开发数据库" showIcon /><h2 className="snapshot-detail-id">{selected.eventId}</h2><Descriptions column={2} layout="vertical" items={[{ key: 'status', label: '投递状态', children: <State status={selected.status} /> }, { key: 'endpoint', label: '接收端', children: selected.endpointName }, { key: 'type', label: '事件类型', children: selected.eventType }, { key: 'created', label: '创建时间', children: format(selected.createdAt) }, { key: 'retry', label: '原计划重试时间', children: selected.nextRunAt ? `${format(selected.nextRunAt)}（已冻结，不会执行）` : '不适用' }, { key: 'attempts', label: '轮次 / 尝试', children: `第 1 轮 · ${selected.attempts.length} / 3 次` }]} />
        <section className="snapshot-detail-section"><h2>事件内容</h2><pre>{JSON.stringify(selected.payload, null, 2)}</pre></section>
        <section className="snapshot-detail-section"><h2>尝试历史（演示）</h2>{selected.attempts.length ? selected.attempts.map((attempt) => <div className="snapshot-attempt" key={attempt.attemptNo}><strong>第 {attempt.attemptNo} 次 · HTTP {attempt.httpStatus}</strong><span>{attempt.result === 'SUCCESS' ? '接收端确认接收' : '接收端返回非成功状态'}</span><small>{format(attempt.startedAt)} · 耗时 100 ms</small></div>) : <p className="snapshot-muted">{selected.status === 'PAUSED' ? '接收端停用，首次投递前暂停。' : '等待首次投递，尚无尝试记录。'}</p>}</section></>}
    </Drawer>
  </ConfigProvider>;
}
