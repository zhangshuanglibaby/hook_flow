import { useRef, useState } from 'react';
import { App, Button, Checkbox, Form, Input, InputNumber, Modal, Select } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/resources';
import { ApiError } from '../../shared/api/client';
import type { Endpoint } from '../../shared/api/schemas';
import { isCurrent } from '../../shared/api/session';
import { useSessionKey, useWrite } from '../../shared/query';
import { integerError, textError, urlError } from '../../shared/format';
import { ErrorNotice, InfoNotice, LoadingState, PageHeading, ServerTable, StatusTag, WarningNotice } from '../../shared/ui';

interface Values { name: string; targetType: 'EXTERNAL' | 'SIMULATOR'; url?: string; timeoutMs: number; enabled: boolean }
// 接收端创建和编辑共用这个弹窗组件。
// 编辑时只提交真正发生变化的字段，避免误修改 targetType、enabled 或历史任务快照。
function EndpointEditor({ endpoint, onClose, onSecret }: { endpoint?: Endpoint; onClose: () => void; onSecret: (secret: string) => void }) {
  const [form] = Form.useForm<Values>();
  // Form.useWatch 让选择目标类型后立即显示或隐藏 URL 输入框。
  const targetType = Form.useWatch('targetType', form) ?? endpoint?.targetType ?? 'EXTERNAL';
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  // 写请求结果不明确时，禁止用户继续点击提交，避免重复创建接收端。
  const [uncertain, setUncertain] = useState(false);
  // 即使按钮还没有来得及 disabled，也用 guard 阻止重复调用 submit。
  const guard = useRef(false);
  const generation = useSessionKey();
  const client = useQueryClient();
  const { message } = App.useApp();

  async function submit(values: Values) {
    if (guard.current) return;
    let body: Record<string, unknown>;

    if (endpoint) {
      // 编辑接口只允许提交 name、url、timeoutMs，而且只提交有变化的字段。
      body = {};
      if (values.name !== endpoint.name) body.name = values.name;
      if (values.timeoutMs !== endpoint.timeoutMs) body.timeoutMs = values.timeoutMs;
      if (endpoint.targetType === 'EXTERNAL' && values.url !== endpoint.url) body.url = values.url;
      if (!Object.keys(body).length) {
        setError(new Error('请至少修改一项配置。'));
        return;
      }
    } else {
      // 新建模拟器不能传 url；新建外部端点才需要 url。
      body = {
        name: values.name,
        targetType: values.targetType,
        timeoutMs: values.timeoutMs,
        enabled: values.enabled,
        ...(values.targetType === 'EXTERNAL' ? { url: values.url } : {}),
      };
    }

    guard.current = true;
    setBusy(true);
    setError(null);
    try {
      if (endpoint) {
        await api.updateEndpoint(endpoint.id, JSON.stringify(body));
      } else {
        // 创建外部接收端的响应可能包含一次性 signingSecret。
        const created = await api.createEndpoint(JSON.stringify(body));
        if (!isCurrent(generation)) return;
        if (created.signingSecret) onSecret(created.signingSecret);
      }
      if (!isCurrent(generation)) return;
      void message.success(endpoint ? '接收端配置已更新，旧任务快照保持不变。' : '接收端已创建。');
      onClose();
    } catch (cause) {
      if (!isCurrent(generation)) return;
      const failure = cause instanceof Error ? cause : new Error('保存失败。');
      setError(failure);
      setUncertain(failure instanceof ApiError && failure.uncertain);
    } finally {
      if (isCurrent(generation)) {
        // 配置变更后让列表和审计重新读取服务端数据。
        await Promise.all(['endpoints', 'audit'].map((name) => client.invalidateQueries({ queryKey: [generation, name] })));
        guard.current = false;
        setBusy(false);
      }
    }
  }

  // 把不同字段的校验器包装成 Ant Design Form 需要的 validator 格式。
  const validate = <T,>(checker: (value: T) => string | undefined) => ({
    validator: async (_: unknown, value: T) => {
      const validationError = checker(value);
      if (validationError) throw new Error(validationError);
    },
  });
  return <Modal title={endpoint ? '编辑接收端' : '创建接收端'} open onCancel={() => { if (!busy) onClose(); }} maskClosable={false} keyboard={!busy} closable={!busy} footer={<div className="modal-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button type="primary" loading={busy} disabled={uncertain} onClick={() => form.submit()}>{endpoint ? '保存更改' : '创建接收端'}</Button></div>}><p className="subtle">配置仅作用于后续新任务，旧任务继续使用原快照。</p><ErrorNotice error={error} />{uncertain && <p>接收端可能已创建或更新。请关闭弹窗查询列表；外部密钥若未取得，不能通过查询找回，也不要自动重建。</p>}<Form form={form} layout="vertical" disabled={busy} onFinish={submit} initialValues={endpoint ?? { targetType: 'EXTERNAL', timeoutMs: 5000, enabled: true }}>
    <Form.Item label="名称" name="name" rules={[validate((value: string) => textError(value ?? '', 100) ?? (!(value ?? '').trim() ? '名称不能全为空白。' : undefined))]}><Input placeholder="如 积分服务" /></Form.Item>
    <Form.Item label="目标类型" name="targetType" extra="创建后不可更改。"><Select disabled={Boolean(endpoint) || busy} options={[{ value: 'EXTERNAL', label: '外部 HTTPS 接收端' }, { value: 'SIMULATOR', label: '内置故障模拟器' }]} /></Form.Item>
    {targetType === 'EXTERNAL' ? <Form.Item label="HTTP URL" name="url" preserve={false} rules={[validate((value: string) => urlError(value ?? ''))]} extra="公开 HTTPS，无凭据、查询串或片段。DNS 与实际连接 IP 由后端校验。"><Input placeholder="https://hooks.example.com/events" /></Form.Item> : <InfoNotice message="使用系统固定内部目标，不传 URL 字段。" />}
    <Form.Item label="请求超时（毫秒）" name="timeoutMs" rules={[validate((value: number | null) => integerError(value, 1, 15000))]}><InputNumber min={1} max={15000} /></Form.Item>{!endpoint && <Form.Item name="enabled" valuePropName="checked"><Checkbox>创建后启用</Checkbox></Form.Item>}
  </Form></Modal>;
}
function SecretDelivery({ secret, onClose }: { secret: string; onClose: () => void }) {
  const [saved, setSaved] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  async function copy() { try { await navigator.clipboard.writeText(secret); setCopied(true); } catch { setError(new Error('无法写入剪贴板，请手动选中复制。')); } }
  return <Modal title="请保存一次性验签密钥" open closable={false} maskClosable={false} keyboard={false} footer={<Button type="primary" disabled={!saved} onClick={onClose}>已安全保存，关闭</Button>}><WarningNotice message="仅本次可见，关闭后无法找回。" /><p>通过安全渠道交给接收端负责人，不要放入代码、URL 或日志。复制后请注意清理系统剪贴板。</p><Input.TextArea readOnly value={secret} aria-label="一次性验签密钥" autoComplete="off" className="mono" rows={3} /><Button onClick={() => void copy()}>{copied ? '已复制' : '复制密钥'}</Button><ErrorNotice error={error} /><p><Checkbox checked={saved} onChange={(event) => setSaved(event.target.checked)}>我已通过安全渠道保存该密钥</Checkbox></p></Modal>;
}
export function EndpointsPage() {
  const generation = useSessionKey();
  // 页码和每页数量只控制服务端列表查询，不在浏览器本地切片。
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  // undefined=没有打开弹窗，null=创建，Endpoint对象=编辑。
  const [editing, setEditing] = useState<Endpoint | null | undefined>();
  const [confirming, setConfirming] = useState<Endpoint | null>(null);
  // signingSecret 只存在这个即时交付弹窗的状态中，不进入 Query 缓存。
  const [secret, setSecret] = useState<string | null>(null);
  const { message } = App.useApp();
  const query = useQuery({
    queryKey: [generation, 'endpoints', 'list', page, size],
    queryFn: ({ signal }) => api.endpoints(page, size, signal),
  });
  // 启停成功后让接收端、事件、概览和审计重新读取服务端状态。
  const toggle = useWrite({
    mutationFn: (endpoint: Endpoint) => api.toggleEndpoint(endpoint.id, !endpoint.enabled),
    onSuccess: (endpoint) => {
      setConfirming(null);
      void message.success(endpoint.enabled ? '接收端已启用。' : '接收端已停用。');
    },
  }, ['endpoints', 'events', 'overview', 'audit']);
  const columns: TableColumnsType<Endpoint> = [
    { title: '接收端', width: 170, render: (_, endpoint) => <><strong>{endpoint.name}</strong><span className="cell-secondary">ID {endpoint.id} · {endpoint.targetType === 'SIMULATOR' ? '内置模拟器' : '外部目标'}</span></> },
    { title: '目标地址 / 类型', width: 320, render: (_, endpoint) => <span className="mono endpoint-url">{endpoint.url ?? '系统固定内部目标'}</span> },
    { title: '请求超时', dataIndex: 'timeoutMs', width: 130, render: (n: number) => `${n.toLocaleString('zh-CN')} ms` },
    { title: '状态', dataIndex: 'enabled', width: 120, render: (enabled: boolean) => <StatusTag status={enabled ? 'SUCCESS' : 'PAUSED'} text={enabled ? '已启用' : '已停用'} /> },
    { title: '操作', width: 150, render: (_, endpoint) => <div className="row-actions"><Button type="link" onClick={() => setEditing(endpoint)}>编辑</Button><Button type="link" danger={endpoint.enabled} onClick={() => { toggle.reset(); setConfirming(endpoint); }}>{endpoint.enabled ? '停用' : '启用'}</Button></div> },
  ];
  return <><PageHeading title="接收端管理" description="配置事件的目的地，保留每一轮任务的配置边界。" action={<Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>创建接收端</Button>} /><InfoNotice message="编辑不覆盖已有任务快照；停用暂停未发任务，在途请求允许结束。接收端不提供删除。" /><section className="event-panel page-section"><div className="table-caption"><strong>接收端配置</strong><Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => void query.refetch()}>刷新列表</Button></div><ErrorNotice error={query.error} stale={Boolean(query.data)} retry={() => void query.refetch()} />{query.isPending ? <LoadingState /> : query.data && <ServerTable data={query.data} columns={columns} label="接收端配置列表" onPage={(next, count) => { setPage(next); setSize(count); }} loading={query.isFetching} />}</section>
    {editing !== undefined && <EndpointEditor endpoint={editing ?? undefined} onClose={() => setEditing(undefined)} onSecret={setSecret} />}{secret && <SecretDelivery secret={secret} onClose={() => setSecret(null)} />}
    <Modal title={confirming?.enabled ? '停用这个接收端？' : '启用这个接收端？'} open={Boolean(confirming)} onCancel={() => { if (!toggle.isPending) setConfirming(null); }} maskClosable={false} keyboard={!toggle.isPending} closable={!toggle.isPending} footer={<div className="modal-actions"><Button disabled={toggle.isPending} onClick={() => setConfirming(null)}>取消</Button><Button danger={confirming?.enabled} type="primary" loading={toggle.isPending} disabled={toggle.error instanceof ApiError && toggle.error.uncertain} onClick={() => confirming && toggle.submit(confirming)}>确认{confirming?.enabled ? '停用' : '启用'}</Button></div>}><p>{confirming?.name}</p><WarningNotice message={confirming?.enabled ? '拒绝新事件与重投，暂停未发任务；正在投递的请求可结束。' : '暂停任务按原快照恢复，最终失败的旧轮不会重开。'} /><ErrorNotice error={toggle.error} />{toggle.error instanceof ApiError && toggle.error.uncertain && <p>请关闭后刷新列表，确认状态再操作。</p>}</Modal></>;
}
