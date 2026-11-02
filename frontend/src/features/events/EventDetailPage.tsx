import { useState } from 'react';
import { App, Button, Drawer, Modal } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { attemptLabels } from '../../data/labels';
import type { AttemptResult } from '../../data/types';
import type { EventDetail } from '../../shared/api/schemas';
import { api } from '../../shared/api/resources';
import { ApiError } from '../../shared/api/client';
import { payloadText } from '../../shared/api/json';
import { formatTime } from '../../shared/format';
import { eventPathSafe } from '../../shared/navigation';
import { useSessionKey, useWrite } from '../../shared/query';
import { ErrorNotice, InfoNotice, LoadingState, NotFound, StatusTag, WarningNotice } from '../../shared/ui';

// 详情内容负责展示“事件 → 轮次 → 尝试”的完整层级。
// event 来自后端详情接口，不在浏览器里自行创建轮次或尝试记录。
function DetailContent({ event, refresh, refreshing }: { event: EventDetail; refresh: () => void; refreshing: boolean }) {
  const generation = useSessionKey();
  // 控制手动重投的二次确认弹窗。
  const [retryOpen, setRetryOpen] = useState(false);
  const { message } = App.useApp();
  // 详情需要读取当前接收端，用来判断是否允许发起新的投递轮次。
  const endpoint = useQuery({
    queryKey: [generation, 'endpoints', 'detail', event.endpointId],
    queryFn: ({ signal }) => api.endpoint(event.endpointId, signal),
  });
  // 重投成功后只刷新服务端数据，不在前端伪造新轮次。
  const mutation = useWrite({
    mutationFn: () => api.redeliver(event.eventId),
    onSuccess: () => {
      setRetryOpen(false);
      void message.success('新一轮投递已受理，旧轮历史已保留。');
    },
    onError: () => { void endpoint.refetch(); },
  }, ['events', 'overview', 'audit']);
  const latest = event.rounds.find((round) => round.roundNo === event.latestRoundNo);
  // 只有最新轮最终失败、接收端启用且事件 ID 可安全放入路径时才能重投。
  const canRetry = event.status === 'FINAL_FAILED' && endpoint.data?.enabled === true && eventPathSafe(event.eventId);
  return <div className="detail-content"><p className="detail-id mono">{event.eventId}</p><div className="detail-status-bar"><div><StatusTag status={event.status} /><span className="subtle">第 {event.latestRoundNo} 轮 · {latest?.attemptCount ?? '—'} / {latest?.maxAttempts ?? '—'} 次尝试</span></div><div><Button icon={<ReloadOutlined />} loading={refreshing} onClick={() => { refresh(); void endpoint.refetch(); }}>刷新</Button><Button type="primary" disabled={!canRetry || refreshing || endpoint.isError || endpoint.isFetching} onClick={() => { mutation.reset(); setRetryOpen(true); }}>手动重投</Button></div></div><p className="subtle">仅最新轮最终失败且接收端启用时可重投。{endpoint.data?.enabled === false ? '请先到接收端管理启用目标。' : ''}</p><ErrorNotice error={endpoint.error} retry={() => void endpoint.refetch()} />
    {event.status === 'FINAL_FAILED' ? <WarningNotice message="本轮已结束，但未确认成功。接收端可能已处理，请先排查历史记录。" /> : event.status === 'SUCCESS' ? <InfoNotice message="接收端已确认接收，不代表其业务处理完成。" /> : event.status === 'PAUSED' ? <InfoNotice message="未发出的任务已暂停，启用接收端后按原快照继续。" /> : null}
    <dl className="detail-meta"><div><dt>事件类型</dt><dd className="mono">{event.eventType}</dd></div><div><dt>创建时间</dt><dd>{formatTime(event.createdAt)}</dd></div><div><dt>接收端</dt><dd>{endpoint.data?.name ?? `接收端 ID ${event.endpointId}`}{endpoint.data?.enabled === false ? '（已停用）' : ''}</dd></div><div><dt>当前配置（非历史快照）</dt><dd>{endpoint.data ? <>{endpoint.data.url ?? '系统固定模拟目标'}<br />超时 {endpoint.data.timeoutMs} ms</> : '尚未取得配置'}</dd></div></dl>
    <section className="detail-section"><div className="section-heading"><h2>事件内容</h2><span className="subtle">JSON · 数值精度保留</span></div><pre className="json-block">{payloadText(event.payload)}</pre></section>
    <section className="detail-section"><div className="section-heading"><h2>投递历史</h2><span className="subtle">{event.rounds.length} 轮 · 旧轮只读保留</span></div>{[...event.rounds].sort((a, b) => a.roundNo - b.roundNo).map((round) => <details className="round-card" key={round.taskId} open><summary className="round-heading"><span>第 {round.roundNo} 轮{round.roundNo === event.latestRoundNo && <span className="latest-tag">最新轮</span>}</span><StatusTag status={round.status} /></summary><div className="round-foot">已尝试 {round.attemptCount} / {round.maxAttempts} 次 · 下次执行：{round.nextRunAt ? formatTime(round.nextRunAt) : '—'}<br />创建：{formatTime(round.createdAt)} · 结束：{round.finishedAt ? formatTime(round.finishedAt) : '尚未结束'}</div>{round.attempts.length === 0 && <p className="round-empty">尚无尝试记录。</p>}{[...round.attempts].sort((a, b) => a.attemptNo - b.attemptNo).map((attempt) => <details className="attempt" key={attempt.attemptNo} open={attempt.result === 'UNKNOWN' ? true : undefined}><summary><span className="attempt-no">{attempt.attemptNo}</span><strong className={`attempt-result result-${Object.hasOwn(attemptLabels, attempt.result) ? attempt.result : 'unknown'}`}>{Object.hasOwn(attemptLabels, attempt.result) ? attemptLabels[attempt.result as AttemptResult] : `未知结果：${attempt.result}`}</strong><span className="subtle">HTTP {attempt.httpStatus ?? '—'} · {attempt.durationMs === null ? '耗时待定' : `${attempt.durationMs} ms`}</span></summary><dl className="attempt-detail"><div><dt>开始时间</dt><dd>{formatTime(attempt.startedAt)}</dd></div><div><dt>结束时间</dt><dd>{attempt.endedAt ? formatTime(attempt.endedAt) : '尚未结束'}</dd></div><div><dt>HTTP 状态码</dt><dd>{attempt.httpStatus ?? '—（未确认响应）'}</dd></div><div><dt>错误代码</dt><dd>{attempt.errorCode ?? '—'}</dd></div><div className="response"><dt>响应摘要（最多 4 KiB）</dt><dd className="response-text">{attempt.responseExcerpt ?? '尚未获得响应摘要。'}</dd></div>{attempt.errorMessage && <div className="response"><dt>错误信息</dt><dd>{attempt.errorMessage}</dd></div>}{attempt.result === 'UNKNOWN' && <div className="response"><dd>结果未知，接收端可能已经处理；后续重试需要接收端保证幂等。</dd></div>}</dl></details>)}<div className="round-foot">历史 URL、超时与退避快照：当前接口暂未提供。不会用当前接收端配置替代。</div></details>)}</section>
    <Modal title="创建新一轮投递？" open={retryOpen} onCancel={() => { if (!mutation.isPending) setRetryOpen(false); }} maskClosable={false} keyboard={!mutation.isPending} closable={!mutation.isPending} footer={<div className="modal-actions"><Button disabled={mutation.isPending} onClick={() => setRetryOpen(false)}>取消</Button><Button type="primary" loading={mutation.isPending} disabled={!canRetry || endpoint.isError || endpoint.isFetching || Boolean(mutation.error instanceof ApiError && mutation.error.uncertain)} onClick={() => mutation.submit(undefined)}>确认创建新轮次</Button></div>}><p className="mono">{event.eventId}</p><p>保留事件 ID 和全部历史，使用发起时的最新接收端配置创建新轮次。</p><WarningNotice message="接收端可能已处理，请确保按事件 ID 幂等。" /><ErrorNotice error={mutation.error} />{mutation.error instanceof ApiError && mutation.error.uncertain && <p>请关闭确认框，刷新详情确认最新轮次，不要重复发起。</p>}</Modal>
  </div>;
}
export function EventDetailPage({ drawer = false }: { drawer?: boolean }) {
  // useParams 读取浏览器地址中的事件 ID；它是字符串，不能当数字处理。
  const { eventId = '' } = useParams();
  const generation = useSessionKey();
  const navigate = useNavigate();
  const location = useLocation();
  // 后端当前对部分保留字符的详情路径会被安全链拒绝，因此先在前端拦截。
  const safe = eventPathSafe(eventId);
  const query = useQuery({
    queryKey: [generation, 'events', 'detail', eventId],
    queryFn: ({ signal }) => api.event(eventId, signal),
    enabled: safe,
  });
  const state = location.state as { background?: { pathname: string; search: string }; triggerId?: string } | null;

  // 关闭详情时回到原事件列表，并尝试把焦点还给打开详情的链接。
  function close() {
    navigate(state?.background?.pathname === '/events' ? `/events${state.background.search}` : '/events', { replace: true });
    requestAnimationFrame(() => ((state?.triggerId ? document.getElementById(state.triggerId) : null) ?? document.querySelector<HTMLElement>('h1'))?.focus());
  }

  // 将“不安全路径”“404”“加载失败”“正常详情”分开显示，避免错误被当成空数据。
  const content = !safe
    ? <WarningNotice message="该事件 ID 含路径保留字符，当前后端详情路径暂不支持。请返回列表使用完整 ID 精确查询。" />
    : query.error instanceof ApiError && query.error.httpStatus === 404
      ? <NotFound detail />
      : <><ErrorNotice error={query.error} stale={Boolean(query.data)} retry={() => void query.refetch()} />{query.isPending ? <LoadingState /> : query.data && <DetailContent event={query.data} refresh={() => void query.refetch()} refreshing={query.isFetching} />}</>;
  return drawer ? <Drawer className="detail-drawer" title="每一轮投递，都有记录" open width="min(760px, 100vw)" onClose={close} keyboard autoFocus>{content}</Drawer> : <section className="standalone-detail"><div className="section-heading"><h1>投递详情</h1><Button onClick={close}>返回事件管理</Button></div>{content}</section>;
}
