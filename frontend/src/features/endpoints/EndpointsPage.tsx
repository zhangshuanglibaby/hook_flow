// 这里只保存弹窗与分页界面状态，不修改接收端配置或关联任务。
import { useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, InputNumber, Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { endpointSamples } from '../../data/samples';
import type { EndpointSample } from '../../data/types';
import { integerError, textError, urlError } from '../../shared/format';
import { PageHeading, PreviewModal, SampleTable, StaticNotice, StatusTag } from '../../shared/ui';

// 创建和编辑共用静态表单，目标类型只影响 URL 参数区可见性。
function EndpointForm({ endpoint }: { endpoint?: EndpointSample }) {
  // 控制 URL 区域展示；编辑模式使用已确定且不可更改的目标类型。
  const [targetType, setTargetType] = useState(endpoint?.targetType ?? 'EXTERNAL');
  // 检查名称码点数，错误仅出现在当前输入下方。
  async function validateName(_: unknown, value: string) {
    // 空值转为字符串，避免尚未输入时调用 trim 报错。
    const error = textError((value ?? '').trim(), 100);
    // 交给组件库展示校验结果，不保存输入。
    if (error) throw new Error(error);
  }
  // 检查 URL 的基本格式，不触发网络探测。
  async function validateUrl(_: unknown, value: string) {
    // 本地格式校验不是 DNS、白名单或实际连接 IP 校验。
    const error = urlError(value ?? '');
    // 把具体格式说明显示到当前字段。
    if (error) throw new Error(error);
  }
  // 检查超时整数范围，不用本地超时模拟真实请求。
  async function validateTimeout(_: unknown, value: number | null) {
    // 默认 5 秒但允许 1–15000 整数毫秒。
    const error = integerError(value, 1, 15000);
    // 参数无效时只阻止字段校验通过。
    if (error) throw new Error(error);
  }
  // 类型切换只影响表单布局，不改变已保存配置或表格。
  function changeType(value: 'EXTERNAL' | 'SIMULATOR') {
    // 保存草稿类型以控制 URL 显示与隐藏。
    setTargetType(value);
  }
  // 不设置提交回调，不产生新接收端、密钥或审计。
  return <Form layout="vertical" initialValues={{ name: endpoint?.name, targetType, url: endpoint?.url ?? '', timeoutMs: endpoint?.timeoutMs ?? 5000, enabled: true }}>
    <p className="subtle">编辑只影响后续新任务；已有任务保留原快照。此表单不保存任何更改。</p>
    <Form.Item label="名称" name="name" rules={[{ validator: validateName }]}><Input placeholder="如 积分服务" /></Form.Item>
    <Form.Item label="目标类型" name="targetType" extra="创建后目标类型不可更改。"><Select disabled={Boolean(endpoint)} onChange={changeType} options={[{ value: 'EXTERNAL', label: '外部 HTTPS 接收端' }, { value: 'SIMULATOR', label: '内置故障模拟器' }]} /></Form.Item>
    {/* 内置模拟器不显示自定义内部 URL；字段卸载后不保留无关校验。 */}
    {targetType === 'EXTERNAL' ? <Form.Item label="HTTP URL" name="url" preserve={false} rules={[{ validator: validateUrl }]} extra="仅校验格式；服务端仍需验证白名单、DNS 和实际连接 IP。"><Input placeholder="https://hooks.example.com/events" /></Form.Item> : <Alert type="info" showIcon message="使用系统固定内部目标，不接受自定义内部 URL。" />}
    <Form.Item label="请求超时（毫秒）" name="timeoutMs" rules={[{ validator: validateTimeout }]} extra="1–15,000 ms，默认 5,000 ms。"><InputNumber min={1} max={15000} precision={0} /></Form.Item>
    {/* 编辑不包含 enabled，避免把启停混入正式 PATCH 设计。 */}
    {!endpoint && <Form.Item name="enabled" valuePropName="checked"><Checkbox>创建后启用</Checkbox></Form.Item>}
    <p className="subtle">本轮不生成或保存验签密钥。正式外部接收端创建后的“一次性密钥交付”待真实接口接入。</p>
  </Form>;
}
// 管理页提供创建、编辑、启停的表单/确认预览，最终执行按钮禁用。
export function EndpointsPage() {
  // undefined 表示弹窗关闭；null 表示创建；对象表示编辑相应样例。
  const [editing, setEditing] = useState<EndpointSample | null | undefined>(undefined);
  // 保存当前启停确认对象，不改变该对象的 enabled 字段。
  const [confirming, setConfirming] = useState<EndpointSample | null>(null);
  // 分页默认 20，保持正式方案的每页默认值。
  const [page, setPage] = useState(1);
  // 仅本地呈现需要每页条数状态。
  const [size, setSize] = useState(20);
  // 关闭配置弹窗时丢弃草稿，而不保存到静态样例。
  function closeEditing() {
    // 使用 undefined 统一表示创建和编辑弹窗都已关闭。
    setEditing(undefined);
  }
  // 关闭启停确认，不会修改任何关联投递任务。
  function closeConfirming() {
    // 清空当前确认对象，使浮层隐藏。
    setConfirming(null);
  }
  // 本地分页只更新显示范围。
  function changePage(nextPage: number, nextSize: number) {
    // 数量变化回第一页，以免页码超界。
    setPage(nextSize === size ? nextPage : 1);
    // 更新当前样例每页显示数量。
    setSize(nextSize);
  }
  // 所有表格内容取自集中静态输入，无接口和读写服务。
  const columns: TableColumnsType<EndpointSample> = [
    { title: '接收端', dataIndex: 'name', width: 170, render: (name: string, endpoint) => <><strong>{name}</strong><span className="cell-secondary">{endpoint.targetType === 'SIMULATOR' ? '内置接收目标' : '外部接收目标'}</span></> },
    { title: '目标地址 / 类型', key: 'url', width: 320, render: (_, endpoint) => <div className="endpoint-url"><span className="mono">{endpoint.url ?? '系统固定内部目标'}</span><span className="cell-secondary">{endpoint.targetType === 'SIMULATOR' ? '由系统管理，不接受自定义内部 URL' : '仅允许预先配置的公开 HTTPS 目标'}</span></div> },
    { title: '请求超时', dataIndex: 'timeoutMs', width: 120, render: (value: number) => `${value.toLocaleString('zh-CN')} ms` },
    { title: '状态', dataIndex: 'enabled', width: 115, render: (enabled: boolean) => <StatusTag status={enabled ? 'SUCCESS' : 'PAUSED'} text={enabled ? '已启用' : '已停用'} /> },
    { title: '操作', key: 'actions', width: 145, render: (_, endpoint) => (
      // 两个事件绑定都只选中对应浮层，不触发业务状态更新。
      <div className="row-actions"><Button type="link" onClick={() => setEditing(endpoint)} aria-label={`编辑${endpoint.name}`}>编辑</Button><Button type="link" danger={endpoint.enabled} onClick={() => setConfirming(endpoint)} aria-label={`${endpoint.enabled ? '停用' : '启用'}${endpoint.name}`}>{endpoint.enabled ? '停用' : '启用'}</Button></div>
    ) },
  ];
  // 标题中的创建按钮只打开空白表单，不插入列表记录。
  return <><PageHeading title="接收端管理" description="配置事件的目的地，让每一条投递有据可依。" action={<Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>创建接收端</Button>} /><StaticNotice>静态展示 / 未接入后端。编辑不影响已有任务快照；启停按钮仅打开确认，不修改样例。</StaticNotice>
    <section className="event-panel page-section"><div className="table-caption"><strong>接收端配置 <span className="subtle">3 个接收端样例</span></strong><span className="subtle">接收端不提供删除</span></div><SampleTable rows={endpointSamples} columns={columns} label="接收端配置列表" page={page} size={size} onPage={changePage} /></section>
    {/* 按打开状态挂载表单，每次重新打开读取原始样例，不伪保存上次草稿。 */}
    <PreviewModal title={editing ? '编辑接收端' : '创建接收端'} open={editing !== undefined} onClose={closeEditing} action={editing ? '保存更改' : '创建接收端'}>{editing !== undefined && <EndpointForm key={editing?.id ?? 'new'} endpoint={editing ?? undefined} />}</PreviewModal>
    {/* 确认内容随启停语义变化，最终按钮始终禁用。 */}
    <PreviewModal title={confirming?.enabled ? '停用这个接收端？' : '启用这个接收端？'} open={confirming !== null} onClose={closeConfirming} action={confirming?.enabled ? '确认停用' : '确认启用'}><p>{confirming?.name}</p><Alert showIcon type={confirming?.enabled ? 'warning' : 'info'} message={confirming?.enabled ? '停用后拒绝新事件及手动重投，未发旧任务暂停；进行中的请求允许结束。' : '暂停任务按原快照恢复。最终失败的旧轮不会自动重开。'} /></PreviewModal>
  </>;
}
