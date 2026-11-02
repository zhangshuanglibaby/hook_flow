import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Select } from 'antd';
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { statusLabels } from '../../data/labels';
import { formatTime } from '../../shared/format';
import { ErrorNotice, LoadingState, PageHeading, ServerTable, StatusTag } from '../../shared/ui';
import { api } from '../../shared/api/resources';
import type { EventSummary } from '../../shared/api/schemas';
import { useSessionKey } from '../../shared/query';
import { EndpointSelect } from '../../shared/EndpointSelect';
import { EndpointName } from '../../shared/EndpointName';
import { queryString } from '../../shared/api/client';
import { eventPathSafe } from '../../shared/navigation';
import { localDateToUtc, rangeError, utcToLocalDate } from '../../shared/time';
import { CreateEventModal } from './CreateEventModal';
import { parseEventFilters } from './filters';

// 表单中的筛选草稿。只有点击“查询”后，才会写入 URL 并触发请求。
type FilterDraft = { eventId?: string; eventType?: string; endpointId?: number; status?: string; from?: string; to?: string };

export function EventsPage() {
  // 控制创建事件弹窗，不在这里保存事件数据；事件由后端创建。
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<FilterDraft>();
  // 记录日期格式或筛选参数的前端错误。
  const [localError, setLocalError] = useState<Error | null>(null);
  // URL 是已经提交的筛选条件，可刷新、复制和浏览器后退恢复。
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  // 会话编号进入 query key，避免不同账号共用缓存。
  const generation = useSessionKey();
  const { filters, error } = parseEventFilters(params);
  // 参数非法时不发请求；参数变化时 TanStack Query 会读取对应的新数据。
  const query = useQuery({
    queryKey: [generation, 'events', 'list', filters],
    queryFn: ({ signal }) => api.events(filters, signal),
    enabled: !error,
  });
  const serialized = params.toString();

  // 当 URL 筛选发生变化时，把已提交条件同步回表单显示。
  useEffect(() => {
    const { filters } = parseEventFilters(new URLSearchParams(serialized));
    form.resetFields();
    form.setFieldsValue({ ...filters, from: utcToLocalDate(filters.from), to: utcToLocalDate(filters.to) });
  }, [serialized, form]);

  // 提交筛选：日期控件显示本地日期，但接口需要 UTC 时间。
  function apply(values: FilterDraft) {
    try {
      // 如果用户没有改日期，就保留 URL 中原来的精确 UTC 边界，避免丢失时间精度。
      const from = values.from === utcToLocalDate(filters.from) ? filters.from : localDateToUtc(values.from ?? '');
      const to = values.to === utcToLocalDate(filters.to) ? filters.to : localDateToUtc(values.to ?? '');
      const invalid = rangeError(from, to);
      if (invalid) throw new Error(invalid);
      // 新筛选从第1页开始，不能继续使用旧筛选的页码。
      const next = { ...values, from, to, page: 1, size: filters.size };
      setParams(queryString(next).slice(1));
      setLocalError(null);
    } catch (cause) {
      setLocalError(cause instanceof Error ? cause : new Error('请检查筛选。'));
    }
  }

  // 清除 URL 和表单中的筛选，重新查询全部事件。
  function reset() {
    setParams('');
    form.resetFields();
    setLocalError(null);
  }

  // 快捷状态筛选同样写入 URL，并回到第一页。
  function shortcut(status?: string) {
    setParams(queryString({ ...filters, page: 1, status }).slice(1));
  }
  const columns: TableColumnsType<EventSummary> = [
    { title: '事件 ID / 类型', width: 280, render: (_, event) => <>{eventPathSafe(event.eventId) ? <Link id={`event-${event.eventId}`} className="event-id mono" to={`/events/${encodeURIComponent(event.eventId)}`} state={{ background: location, triggerId: `event-${event.eventId}` }}>{event.eventId}</Link> : <span className="mono">{event.eventId}<span className="cell-secondary">ID 包含路径保留字符，后端详情路径暂不支持</span></span>}<span className="cell-secondary mono">{event.eventType}</span></> },
    { title: '接收端', dataIndex: 'endpointId', width: 150, render: (id: number) => <EndpointName id={id} /> },
    { title: '投递状态', dataIndex: 'status', width: 140, render: (status: string) => <StatusTag status={status} /> },
    { title: '最新轮次', dataIndex: 'latestRoundNo', width: 120, render: (n: number) => `第 ${n} 轮` },
    { title: '创建时间', dataIndex: 'createdAt', width: 190, render: (time: string) => <span className="date-cell">{formatTime(time)}</span> },
    { title: '操作', width: 120, render: (_, event) => eventPathSafe(event.eventId) ? <Link className="table-link" to={`/events/${encodeURIComponent(event.eventId)}`} state={{ background: location }}>查看详情</Link> : <span className="subtle">路径受限</span> },
  ];
  return <><PageHeading title="事件管理" description="追踪每一条事件，从提交到最终响应。" action={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建测试事件</Button>} />
    <section className="event-panel"><div className="list-top"><div className="quick-filters">{[['', '全部事件'], ['FINAL_FAILED', '最终失败'], ['WAIT_RETRY', '等待重试']].map(([status, title]) => <Button key={status} type="text" className={(filters.status ?? '') === status ? 'quick-active' : ''} onClick={() => shortcut(status || undefined)}>{title}</Button>)}</div><Button icon={<ReloadOutlined />} loading={query.isFetching} disabled={Boolean(error)} onClick={() => void query.refetch()}>刷新列表</Button></div>
    <Form form={form} className="filter-form search-controls" layout="vertical" onFinish={apply}><div className="filter-primary"><Form.Item label="事件 ID" name="eventId"><Input prefix={<SearchOutlined />} placeholder="输入完整事件 ID" /></Form.Item><Form.Item label="事件类型" name="eventType"><Input placeholder="精确匹配，如 order.paid" /></Form.Item><Form.Item label="接收端" name="endpointId"><EndpointSelect /></Form.Item><Form.Item label="投递状态" name="status"><Select allowClear placeholder="全部状态" options={Object.entries(statusLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></div><div className="filter-secondary"><div className="date-group"><span>创建时间</span><Form.Item name="from"><Input type="date" aria-label="创建时间起始日期" /></Form.Item><span>至</span><Form.Item name="to"><Input type="date" aria-label="创建时间结束日期，不包含该日" /></Form.Item><span className="subtle">本地日期 · 结束日不包含</span></div><div className="filter-actions"><Button onClick={reset}>重置</Button><Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button></div></div></Form>
    {(error || localError) && <Alert type="error" showIcon message={error ?? localError?.message} action={<Button onClick={reset}>清除筛选</Button>} />}<ErrorNotice error={query.error} stale={Boolean(query.data)} retry={() => void query.refetch()} />
    <div className="table-caption"><strong>事件列表</strong><span className="subtle">状态取最新轮；次数与最近时间见详情</span></div>{!error && query.isPending ? <LoadingState /> : query.data && <ServerTable data={query.data} columns={columns} rowKey="eventId" label="事件列表" loading={query.isFetching} onPage={(page, size) => setParams(queryString({ ...filters, page, size }).slice(1))} emptyAction={<><Button onClick={reset}>清除筛选</Button><Button type="primary" onClick={() => setCreateOpen(true)}>创建测试事件</Button></>} />}</section>
    <p className="below-table">成功仅表示接收端确认接收，不代表业务处理完成。最终失败表示未确认成功，不代表对方一定未处理。</p>{createOpen && <CreateEventModal onClose={() => setCreateOpen(false)} />}</>;
}
