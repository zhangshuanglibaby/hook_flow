import { useState } from 'react';
import { Button, Input } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { statusLabels } from '../../data/labels';
import type { DeliveryStatus } from '../../data/types';
import { api } from '../../shared/api/resources';
import { queryString } from '../../shared/api/client';
import { useSessionKey } from '../../shared/query';
import { defaultRange, localDateToUtc, rangeError, utcToLocalDate } from '../../shared/time';
import { ErrorNotice, InfoNotice, LoadingState, PageHeading, StatusTag } from '../../shared/ui';

export function OverviewPage() {
  // 会话编号进入 query key，确保切换账号后不会复用上一位用户的统计缓存。
  const generation = useSessionKey();
  // range 保存真正提交给后端的 UTC 区间；from/to 只是日期控件中的本地草稿。
  const [range, setRange] = useState(defaultRange);
  const [from, setFrom] = useState(utcToLocalDate(range.from));
  const [to, setTo] = useState(utcToLocalDate(range.to));
  const [error, setError] = useState<Error | null>(null);
  // range 变化才会重新读取概览，日期输入过程不会频繁请求后端。
  const query = useQuery({ queryKey: [generation, 'overview', range], queryFn: ({ signal }) => api.overview(range.from, range.to, signal) });

  // 把本地日期转换成后端要求的 UTC 半开区间并执行查询。
  function apply() {
    try {
      const nextFrom = localDateToUtc(from);
      const nextTo = localDateToUtc(to);
      if (!nextFrom || !nextTo) throw new Error('请选择起始与结束日期。');
      const invalid = rangeError(nextFrom, nextTo);
      if (invalid) throw new Error(invalid);
      setRange({ from: nextFrom, to: nextTo });
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause : new Error('日期不正确。'));
    }
  }

  const data = query.data;
  // 这些数字全部来自后端聚合，不从当前事件列表计算，避免只统计当前页。
  const metrics = data ? [
    { label: '事件总数', value: data.eventCount, note: '范围内创建，每事件一次' },
    { label: '投递成功', value: data.statusCounts.SUCCESS, note: '最新轮已确认接收' },
    { label: '等待重试', value: data.statusCounts.WAIT_RETRY, note: '等待下一次自动尝试' },
    { label: '最终失败', value: data.statusCounts.FINAL_FAILED, note: '本轮结束，未确认成功' },
  ] : [];
  return <><PageHeading title="概览" description="按明确的统计口径，定位需要关注的投递。" action={<Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => void query.refetch()}>刷新统计</Button>} /><div className="range-toolbar page-section search-controls"><div><strong>统计时间范围</strong><span className="cell-secondary">本地日期 · 结束日不包含</span></div><div className="range-inputs"><label>起始日期<Input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label><span>至</span><label>结束日期<Input type="date" value={to} onChange={(event) => setTo(event.target.value)} /></label><Button type="primary" onClick={apply}>应用</Button></div></div><ErrorNotice error={error ?? query.error} stale={Boolean(query.data && query.error)} retry={query.error ? () => void query.refetch() : undefined} />{query.isPending ? <LoadingState /> : data && <>
    {data.eventCount === 0 && <InfoNotice message="该创建时间范围内暂无事件；尝试次数仍按独立时间口径统计。" action={<Link to="/events">创建测试事件</Link>} />}<section className="metric-strip" aria-label="事件统计" aria-busy={query.isFetching}>{metrics.map((metric) => <div className="metric-cell" key={metric.label}><span>{metric.label}</span><strong>{metric.value.toLocaleString('zh-CN')}</strong><small>{metric.note}</small></div>)}</section><div className="overview-grid"><section className="surface-panel rate-panel"><div className="section-heading"><h2>终态事件成功率</h2><span className="subtle">仅成功与最终失败参与计算</span></div><div className="rate-number">{data.successRate === null ? '--' : `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(data.successRate * 100)}%`}</div><p>{data.successRate === null ? '尚无终态事件，不显示为 0%。' : `${data.statusCounts.SUCCESS} 个成功 / ${data.statusCounts.SUCCESS + data.statusCounts.FINAL_FAILED} 个终态事件`}</p><div className="definition-box">成功率 = 最新轮成功数 ÷（成功数 + 最终失败数）<br />待投递、投递中、等待重试与暂停均不计入分母。</div><div className="attempt-metric"><strong>{data.attemptCount.toLocaleString('zh-CN')}</strong><span>实际开始的投递尝试</span><p>独立按尝试开始时间统计，包含所有轮次，不受事件创建时间限制。</p></div></section><section className="surface-panel"><div className="section-heading"><h2>事件状态明细</h2><span className="subtle">每个事件的最新一轮</span></div><ul className="status-list">{(Object.keys(statusLabels) as DeliveryStatus[]).map((status) => <li key={status}><StatusTag status={status} /><span className="status-count">{data.statusCounts[status].toLocaleString('zh-CN')} <small>条事件</small></span><Link className="table-link" to={`/events${queryString({ status, ...range })}`}>查看事件</Link></li>)}</ul></section></div></>}
    <p className="below-table">重投可能改变旧时间范围内事件的最新状态。成功仅代表接收端确认，不代表业务完成。统计全部取自后端，不从当前页列表推算。</p></>;
}
