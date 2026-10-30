// 详情只控制重投确认弹窗的开关，历史数据始终只读。
import { useState } from 'react';
import { Alert, Button, Drawer } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { endpointSamples, eventSamples } from '../../data/samples';
import { attemptLabels } from '../../data/labels';
import type { EventSample } from '../../data/types';
import { formatTime } from '../../shared/format';
import { NotFound, PreviewModal, StaticNotice, StatusTag } from '../../shared/ui';

// 展示事件 → 轮次 → 尝试的只读层级，原生 details 支持键盘展开和收起。
function DetailContent({ event }: { event: EventSample }) {
  // 独立确认浮层只用于阅读风险，不会添加新轮次。
  const [retryOpen, setRetryOpen] = useState(false);
  // 查找当前配置，仅在“当前配置”区域展示，不冒充历史快照。
  const endpoint = endpointSamples.find((item) => item.id === event.endpointId);
  // 最新轮最终失败且接收端启用时，可以打开只读确认；最终写入仍禁用。
  const canPreviewRetry = event.status === 'FINAL_FAILED' && endpoint?.enabled === true;
  // 关闭重投确认时不改变旧轮、次数或事件 ID。
  function closeRetry() {
    // 隐藏确认弹窗，并由组件库恢复触发按钮焦点。
    setRetryOpen(false);
  }
  // 显示确认弹窗用于解释正式业务边界，而不是伪造重投成功。
  function openRetry() {
    // 仅打开界面浮层，不运行投递或计时器。
    setRetryOpen(true);
  }
  // 详情以独立组件复用在侧面板和直接打开的路由中。
  return <div className="detail-content"><p className="detail-id mono">{event.eventId}</p><StaticNotice />
    <div className="detail-status-bar"><div><StatusTag status={event.status} /><span className="subtle">第 {event.latestRoundNo} 轮 · {event.attemptCount} / {event.maxAttempts} 次尝试</span></div>
      {/* 非最终失败也保留禁用按钮，旁边解释接口与状态约束。 */}
      <Button type="primary" icon={<ReloadOutlined />} disabled={!canPreviewRetry} onClick={openRetry}>手动重投</Button>
    </div><p className="subtle">重投待接口接入；仅最新轮最终失败且接收端启用时允许发起。当前只能阅读确认提示。</p>
    {/* 根据只读状态解释成功边界，不计算或更新业务状态。 */}
    {event.status === 'FINAL_FAILED' ? <Alert type="warning" showIcon message="最终失败 / 未确认成功" description="本轮已结束，但接收端可能已经处理。请先查看历史；重投可能产生重复副作用。" /> : event.status === 'SUCCESS' ? <Alert type="info" showIcon message="接收端已确认接收，不代表其业务处理完成。" /> : event.status === 'PAUSED' ? <Alert type="info" showIcon message="接收端已停用，未发出的任务暂停，恢复后按原快照继续。" /> : null}
    <dl className="detail-meta"><div><dt>事件类型</dt><dd className="mono">{event.eventType}</dd></div><div><dt>创建时间</dt><dd>{formatTime(event.createdAt)}</dd></div><div><dt>接收端</dt><dd>{endpoint?.name ?? '未提供'}{endpoint?.enabled === false ? '（已停用）' : ''}</dd></div><div><dt>当前接收端配置</dt><dd>{endpoint?.url ?? '系统固定内部目标'}<br />超时 {endpoint?.timeoutMs ?? '—'} ms</dd></div></dl>
    <section className="detail-section"><div className="section-heading"><h2>事件内容</h2><span className="subtle">JSON · 虚构数据</span></div><pre className="json-block">{event.payloadText}</pre></section>
    <section className="detail-section"><div className="section-heading"><h2>投递历史</h2><span className="subtle">{event.rounds.length} 轮 · 可展开轮次及尝试</span></div>
      {/* 遍历显式写定的历史轮次；不根据重投按钮生成任何新轮次。 */}
      {event.rounds.map((round) => <details className="round-card" key={round.roundNo} open><summary className="round-heading"><span>第 {round.roundNo} 轮
        {/* 只有样例标明的最新轮才展示标记。 */}
        {round.roundNo === event.latestRoundNo && <span className="latest-tag">最新轮</span>}</span><StatusTag status={round.status} /></summary>
        {/* 没有尝试时明确尚未开始，不把 0 次展示成成功。 */}
        {round.attempts.length === 0 && <p className="round-empty">等待开始第 1 次投递，尚无尝试记录。</p>}
        {/* 每次尝试可以独立展开；UNKNOWN 默认展开，以免忽略副作用风险。 */}
        {round.attempts.map((attempt) => <details className="attempt" key={attempt.attemptNo} open={attempt.result === 'UNKNOWN' ? true : undefined}><summary><span className="attempt-no">{attempt.attemptNo}</span><strong className={`attempt-result result-${attempt.result}`}>{attemptLabels[attempt.result]}</strong><span className="subtle">HTTP {attempt.httpStatus ?? '—'} · {attempt.durationMs === null ? '耗时待定' : `${attempt.durationMs} ms`}</span></summary><dl className="attempt-detail"><div><dt>开始时间</dt><dd>{formatTime(attempt.startedAt)}</dd></div><div><dt>结束时间</dt><dd>{attempt.endedAt === null ? '尚未结束' : formatTime(attempt.endedAt)}</dd></div><div><dt>HTTP 状态码</dt><dd>{attempt.httpStatus ?? '—（未确认响应）'}</dd></div><div><dt>耗时</dt><dd>{attempt.durationMs === null ? '尚未结束' : `${attempt.durationMs} ms`}</dd></div><div className="response"><dt>{attempt.errorMessage ? '错误信息' : '响应摘要'}</dt><dd>{attempt.errorMessage ?? attempt.responseExcerpt ?? '尚未获得响应。'}
          {/* 超时不等于未处理，必须同时提供文字提醒。 */}
          {attempt.result === 'UNKNOWN' && <p>不能据此判断接收端未处理；重试需由接收端保证幂等。</p>}</dd></div></dl></details>)}
        <div className="round-foot">配置快照（本地样例，正式字段待确认）：{round.snapshotUrl ?? '系统固定模拟接收端'}<br />超时 {round.snapshotTimeoutMs} ms · 最多 {round.maxAttempts} 次（含首次） · 退避 10 秒 / 30 秒</div>
      </details>)}
    </section><StaticNotice>自动重试在同一轮增加尝试；手动重投创建新轮次，保留原事件 ID、旧轮及所有尝试。本页不会推进任务。</StaticNotice>
    {/* 确认只供阅读；禁用的最终按钮确保不会生成假轮次或审计。 */}
    <PreviewModal title="创建新一轮投递？" open={retryOpen} onClose={closeRetry} action="确认创建新轮次"><p className="mono">{event.eventId}</p><p>保留原事件 ID 和全部历史；后续正式重投会使用接收端最新配置。</p><Alert type="warning" showIcon message="接收端可能已经处理过请求。请确保按事件 ID 幂等，避免重复副作用。" /></PreviewModal>
  </div>;
}
// 路由既支持列表背景上的侧面板，也支持刷新后独立详情，不依赖列表缓存。
export function EventDetailPage({ drawer = false }: { drawer?: boolean }) {
  // React Router 已解码路由参数，不再次 decodeURIComponent，避免双重解码。
  const { eventId } = useParams();
  // 只在预先声明的样例中查找，不回退生成不存在的记录。
  const event = eventSamples.find((item) => item.eventId === eventId);
  // 导航用于关闭面板时回到站内列表。
  const navigate = useNavigate();
  // 路由 state 仅保存背景和焦点标识，不含密码或 JSON 输入。
  const location = useLocation();
  // 显式读取本站写入的可选背景信息。
  const state = location.state as { background?: { pathname: string; search: string }; triggerId?: string } | null;
  // 关闭详情后回到原有站内列表，不盲目使用 history.back。
  function closeDetail() {
    // 仅接受事件列表作为背景路径，避免 state 中外部路径导致站外跳转。
    const destination = state?.background?.pathname === '/events' ? `/events${state.background.search}` : '/events';
    // 替换详情地址，保留列表之外的浏览历史；面板随后卸载。
    navigate(destination, { replace: true });
    // 下一帧恢复列表触发链接焦点；直接打开详情时聚焦列表标题。
    requestAnimationFrame(() => {
      // 查找仍然存在的原触发链接，不存在时回退到可聚焦标题。
      const target = (state?.triggerId ? document.getElementById(state.triggerId) : null) ?? document.querySelector<HTMLElement>('h1');
      // 可选链避免因目标尚未挂载产生异常。
      target?.focus();
    });
  }
  // 侧面板宽度在 CSS 内约束为最多 760px，手机覆盖可用宽度。
  if (drawer) return <Drawer className="detail-drawer" title="每一轮投递，都有记录" open width="min(760px, 100vw)" onClose={closeDetail} keyboard autoFocus>
    {/* 未找到记录展示明确详情空态，不用第一条样例冒充当前 ID。 */}
    {event ? <DetailContent event={event} /> : <NotFound detail />}
  </Drawer>;
  // 独立详情带明确返回入口，不要求先访问事件列表。
  return <section className="standalone-detail"><div className="section-heading"><h1>投递详情</h1><Button onClick={closeDetail}>返回事件管理</Button></div>{event ? <DetailContent event={event} /> : <NotFound detail />}</section>;
}
