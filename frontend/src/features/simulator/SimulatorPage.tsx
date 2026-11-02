import { useState } from 'react';
import { Button, InputNumber, Radio } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/resources';
import type { Receipt, SimulatorConfig, SimulatorMode } from '../../shared/api/schemas';
import { modeDescriptions, modeLabels } from '../../data/labels';
import { formatTime, integerError } from '../../shared/format';
import { eventPathSafe } from '../../shared/navigation';
import { ErrorNotice, LoadingState, PageHeading, ServerTable, WarningNotice } from '../../shared/ui';
import { useSessionKey, useWrite } from '../../shared/query';
import { CreateEventModal } from '../events/CreateEventModal';

function ConfigEditor({ config, onSend }: { config: SimulatorConfig; onSend: () => void }) {
  // draft 是用户正在编辑但还没有保存的配置。
  const [draft, setDraft] = useState(config);
  const [validation, setValidation] = useState<Error | null>(null);
  // saved 是后端最后确认保存的配置，用来判断页面是否有未保存修改。
  const [saved, setSaved] = useState(config);
  const mutation = useWrite({
    mutationFn: api.saveConfig,
    onSuccess: (value) => {
      // 保存成功后，以后端返回值为准，而不是继续相信本地输入。
      setSaved(value);
      setDraft(value);
    },
  }, ['simulator', 'audit']);
  const dirty = draft.mode !== saved.mode || draft.failFirstN !== saved.failFirstN || draft.delayMs !== saved.delayMs;
  const known = Object.hasOwn(modeLabels, draft.mode);

  function save() {
    // 不同模式只发送自己需要的参数，其他参数归零，避免后端拒绝无关字段。
    const error = draft.mode === 'FAIL_FIRST_N'
      ? integerError(draft.failFirstN, 0, 10)
      : draft.mode === 'PROCESSED_THEN_DELAY'
        ? integerError(draft.delayMs, 1, 30000)
        : undefined;
    if (error) {
      setValidation(new Error(error));
      return;
    }
    setValidation(null);
    mutation.submit(JSON.stringify({
      mode: draft.mode,
      failFirstN: draft.mode === 'FAIL_FIRST_N' ? draft.failFirstN : 0,
      delayMs: draft.mode === 'PROCESSED_THEN_DELAY' ? draft.delayMs : 0,
    }));
  }
  return <div className="simulator-grid page-section"><section className="surface-panel"><div className="section-heading"><h2>响应行为</h2><span className="subtle">已保存：{modeLabels[saved.mode]}</span></div><fieldset className="mode-options" disabled={mutation.isPending}><legend className="sr-only">选择响应模式</legend><Radio.Group value={draft.mode} onChange={(event) => setDraft({ ...draft, mode: event.target.value as SimulatorMode, failFirstN: 0, delayMs: event.target.value === 'PROCESSED_THEN_DELAY' ? 8000 : 0 })}>{(Object.keys(modeLabels) as SimulatorMode[]).map((mode) => <Radio className="mode-option" key={mode} value={mode}><strong>{modeLabels[mode]}</strong><small>{modeDescriptions[mode]}</small></Radio>)}</Radio.Group></fieldset>
    {draft.mode === 'FAIL_FIRST_N' && <label className="parameter-label">失败次数 N<InputNumber aria-label="失败次数 N" value={draft.failFirstN} min={0} max={10} disabled={mutation.isPending} onChange={(value) => setDraft({ ...draft, failFirstN: value ?? -1 })} /><span className="subtle">0–10；N ≥ 3 时默认首轮会耗尽次数。</span></label>}
    {draft.mode === 'PROCESSED_THEN_DELAY' && <label className="parameter-label">响应延迟（毫秒）<InputNumber aria-label="响应延迟（毫秒）" value={draft.delayMs} min={1} max={30000} disabled={mutation.isPending} onChange={(value) => setDraft({ ...draft, delayMs: value ?? 0 })} /><span className="subtle">1–30,000 ms。必须大于任务快照超时才可能出现 UNKNOWN，延迟过短也是合法配置。</span></label>}
    <ErrorNotice error={validation ?? mutation.error} /><div className="form-bottom"><span className="subtle">{dirty ? '有未保存修改' : `已保存 · ${formatTime(saved.updatedAt)}`}</span><Button type="primary" disabled={!dirty || !known} loading={mutation.isPending} onClick={save}>保存模拟配置</Button></div></section>
    <aside className="surface-panel"><div className="section-heading"><h2>配置与实际结果分开</h2><span className="outline-tag">规则说明</span></div><ol className="behavior-path"><li><span className="path-node" /><strong>保存全局配置</strong><p>配置由所有模拟接收端共享，不与其他联调场景同时切换。</p></li><li><span className="path-node" /><strong>创建测试事件</strong><p>使用已保存配置。创建与配置保存是两次请求，不保证原子。</p></li><li><span className="path-node" /><strong>刷新查看真实历史</strong><p>实际 HTTP、验签、幂等和重试由后端执行；计划 200 不等于发送方收到响应。</p></li></ol><div className="definition-box">500 不记为业务已处理；已处理事件按 ID 幂等确认。默认每轮含首次最多 3 次，退避 10 秒 / 30 秒；实际次数以服务端轮次为准。</div>{dirty && <WarningNotice message="请先保存修改，再生成测试事件。" />}<Button className="send-simulator" type="primary" icon={<PlusOutlined />} disabled={dirty || mutation.isPending} onClick={onSend}>生成测试事件</Button></aside></div>;
}
export function SimulatorPage() {
  const generation = useSessionKey();
  const [eventOpen, setEventOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  // 配置和接收记录是两条独立查询，刷新其中一个不会伪造另一个的结果。
  const config = useQuery({ queryKey: [generation, 'simulator', 'config'], queryFn: ({ signal }) => api.config(signal) });
  const receipts = useQuery({ queryKey: [generation, 'simulator', 'requests', page, size], queryFn: ({ signal }) => api.receipts(page, size, signal) });
  const columns: TableColumnsType<Receipt> = [
    { title: '事件 ID / 接收时间', width: 250, render: (_, record) => <><span className="mono">{record.eventId}</span><span className="cell-secondary">{formatTime(record.receivedAt)}</span></> },
    { title: '响应行为', dataIndex: 'configMode', width: 180, render: (mode: string) => Object.hasOwn(modeLabels, mode) ? modeLabels[mode as SimulatorMode] : `未知模式：${mode}` },
    { title: '计划响应', dataIndex: 'plannedStatus', width: 130, render: (n: number | null) => <span className="http-code">{n === null ? '未计划响应' : `HTTP ${n}`}</span> },
    { title: '校验 / 幂等', width: 220, render: (_, record) => <><span>{record.signatureValid ? '验签通过' : '验签未通过'}</span><span className="cell-secondary">{record.duplicateProcessed ? '已处理，跳过重复副作用' : '未命中已处理事件'}</span></> },
    { title: '操作', width: 120, render: (_, record) => eventPathSafe(record.eventId) ? <Link className="table-link" to={`/events/${encodeURIComponent(record.eventId)}`}>投递详情</Link> : <span>详情路径受限</span> },
  ];
  return <><PageHeading title="故障模拟器" description="在可控响应中，验证真实投递与重试。" action={<Button icon={<ReloadOutlined />} loading={config.isFetching || receipts.isFetching} onClick={() => { void config.refetch(); void receipts.refetch(); }}>刷新</Button>} /><WarningNotice message="模拟器配置全局共享，请串行进行故障联调。所有投递结果来自后端，不在浏览器生成。" /><ErrorNotice error={config.error} stale={Boolean(config.data)} retry={() => void config.refetch()} />{config.isPending ? <LoadingState /> : config.data && <ConfigEditor key={config.data.updatedAt} config={config.data} onSend={() => setEventOpen(true)} />}<section className="event-panel"><div className="table-caption"><strong>模拟接收记录</strong><span className="subtle">计划响应不等于发送方已收到响应</span></div><ErrorNotice error={receipts.error} stale={Boolean(receipts.data)} retry={() => void receipts.refetch()} />{receipts.isPending ? <LoadingState /> : receipts.data && <ServerTable data={receipts.data} columns={columns} label="模拟接收记录" loading={receipts.isFetching} onPage={(next, count) => { setPage(next); setSize(count); }} />}</section>{eventOpen && <CreateEventModal simulator onClose={() => setEventOpen(false)} />}</>;
}
