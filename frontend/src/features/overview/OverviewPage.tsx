// 概览只展示独立写定的聚合样例，不根据本地事件集合计算统计。
import { Button, Input } from 'antd';
import { Link } from 'react-router-dom';
import { overviewSample } from '../../data/samples';
import { statusLabels } from '../../data/labels';
import type { DeliveryStatus } from '../../data/types';
import { PageHeading, StaticNotice, StatusTag } from '../../shared/ui';

// 将固定时间与状态作为导航定位信息；不冒充已经执行了真实筛选。
export function OverviewPage() {
  // 从固定概览中读取全部六状态键，保证页面不遗漏暂停状态。
  const statuses = Object.keys(statusLabels) as DeliveryStatus[];
  // 连续数字分区使用固定的聚合输入，不遍历事件列表求和。
  const metrics = [
    { label: '事件总数', value: overviewSample.eventCount, note: '每个事件仅计一次' },
    { label: '投递成功', value: overviewSample.statusCounts.SUCCESS, note: '最新轮已确认接收' },
    { label: '等待重试', value: overviewSample.statusCounts.WAIT_RETRY, note: '等待下一次自动尝试' },
    { label: '最终失败', value: overviewSample.statusCounts.FINAL_FAILED, note: '本轮结束，未确认成功' },
  ];
  // 日期保持只读，防止日期改变但固定统计不变造成误导。
  return <><PageHeading title="概览" description="从整体状态出发，定位需要关注的投递。" action={<Link className="action-link" to="/events">查看事件</Link>} /><StaticNotice>静态展示 / 未接入后端。以下是独立写定的概览样例，不是本地事件计算结果，不随其他页面操作改变。</StaticNotice><div className="range-toolbar page-section"><div><strong>统计时间范围</strong><span className="cell-secondary">按事件创建时间统计，结束日不包含</span></div><div className="range-inputs"><label>起始日期<Input type="date" readOnly value={overviewSample.from} /></label><span>至</span><label>结束日期<Input type="date" readOnly value={overviewSample.to} /></label><Button disabled>应用 · 待接口接入</Button></div></div>
    <section className="metric-strip" aria-label="固定概览展示样例">
      {/* 遍历已写定的四个数字分区；数字没有业务计算或动态增长。 */}
      {metrics.map((metric) => <div className="metric-cell" key={metric.label}><span>{metric.label}</span><strong>{metric.value}</strong><small>{metric.note}</small></div>)}
    </section><div className="overview-grid"><section className="surface-panel rate-panel"><div className="section-heading"><h2>终态事件成功率</h2><span className="subtle">仅成功与最终失败参与计算</span></div><div className="rate-number">{overviewSample.successRateText}<span>%</span></div><p>2 个成功 / {overviewSample.terminalCount} 个终态事件 · 固定展示样例</p><div className="definition-box">成功率 = 最新轮成功数 ÷（最新轮成功数 + 最终失败数）<br />待投递、投递中、等待重试与暂停中均不计入分母。</div><div className="attempt-metric"><strong>{overviewSample.attemptCount}</strong><span>实际开始的投递尝试（样例）</span><p>独立按尝试开始时间统计，包含所有轮次；不等于事件总数。正式统计完全由后端提供。</p></div></section><section className="surface-panel"><div className="section-heading"><h2>事件状态明细</h2><span className="subtle">每个事件的最新一轮</span></div><ul className="status-list">
      {/* 每个入口携带同一固定范围和所选状态，目标页明确待查询。 */}
      {statuses.map((status) => <li key={status}><StatusTag status={status} /><span className="status-count">{overviewSample.statusCounts[status]} <small>条事件</small></span><Link className="table-link" to={`/events?status=${status}&from=${overviewSample.from}&to=${overviewSample.to}`} aria-label={`定位${statusLabels[status]}事件筛选`}>查看事件</Link></li>)}
    </ul></section></div><p className="below-table">成功仅代表确认接收，不代表接收端业务完成。当前不提供真实健康状态、实时监控或趋势数据。</p></>;
}
