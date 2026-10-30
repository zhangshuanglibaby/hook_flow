// 模拟器仅维护尚未保存的表单草稿，绝不生成投递结果或接收记录。
import { useState } from 'react';
import { Alert, Button, Form, InputNumber, Radio, Select } from 'antd';
import type { RadioChangeEvent, TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { endpointSamples, receiptSamples } from '../../data/samples';
import { modeDescriptions, modeLabels } from '../../data/labels';
import type { ReceiptSample, SimulatorMode } from '../../data/types';
import { formatTime, integerError } from '../../shared/format';
import { PageHeading, PendingAction, PreviewModal, SampleTable, StaticNotice } from '../../shared/ui';

// 右侧路径只是固定规则说明，不根据参数生成新记录。
const paths: Record<SimulatorMode, readonly { title: string; text: string }[]> = {
  ALWAYS_200: [{ title: '首次尝试', text: 'HTTP 200，确认接收。' }],
  ALWAYS_500: [{ title: '首次尝试', text: 'HTTP 500。' }, { title: '等待 10 秒后', text: '第 2 次尝试仍返回 500。' }, { title: '等待 30 秒后', text: '第 3 次仍返回 500，本轮最终失败。' }],
  FAIL_FIRST_N: [{ title: '前 N 次请求', text: '返回 500，不记为业务已处理。N 为 0 时首次即可返回 200。' }, { title: '后续请求', text: '返回 200；一轮最多 3 次，N ≥ 3 时首轮耗尽次数，不能擅自追加尝试。' }],
  PROCESSED_THEN_DELAY: [{ title: '首次尝试', text: '先处理，再延迟响应。' }, { title: '若超过请求超时', text: '尝试结果未知，不能推断未处理。延迟不超过超时时不应误判超时。' }, { title: '后续重试', text: '接收端按事件 ID 幂等，跳过重复副作用并返回 200。' }],
};
// 左侧参数与右侧规则切换保留原型布局，保存和发送保持禁用。
export function SimulatorPage() {
  // 模式只用于选择表单参数区，切换不会改写静态接收历史。
  const [mode, setMode] = useState<SimulatorMode>('ALWAYS_200');
  // 测试事件弹窗只供选择接收端和阅读边界。
  const [eventOpen, setEventOpen] = useState(false);
  // 固定样例本地分页从第一页开始。
  const [page, setPage] = useState(1);
  // 正式默认每页 20 条，但本地分页允许缩小查看样式。
  const [size, setSize] = useState(20);
  // 用完整枚举列表生成四个响应模式。
  const modes = Object.keys(modeLabels) as SimulatorMode[];
  // 只将启用的 SIMULATOR 样例放入测试接收端选项。
  const simulatorOptions = endpointSamples.filter((item) => item.targetType === 'SIMULATOR' && item.enabled).map((item) => ({ value: item.id, label: item.name }));
  // 模式变更只更新本地草稿选择。
  function changeMode(event: RadioChangeEvent) {
    // 值来自固定的四个单选项，不写入任何保存配置。
    setMode(event.target.value as SimulatorMode);
  }
  // N 的整数校验只影响就地字段错误。
  async function validateN(_: unknown, value: number | null) {
    // 当前规则允许 0–10，不把 N≥3 擅自当成非法配置。
    const error = integerError(value, 0, 10);
    // 让表单在字段下方展示错误。
    if (error) throw new Error(error);
  }
  // 延迟只做参数格式校验，不运行计时器。
  async function validateDelay(_: unknown, value: number | null) {
    // 正式参数允许 1–30000 ms，不能按默认超时收紧合法范围。
    const error = integerError(value, 1, 30000);
    // 参数越界时显示对应输入提示。
    if (error) throw new Error(error);
  }
  // 关闭发送表单并丢弃界面，不生成预设历史。
  function closeEvent() {
    // 只有弹窗可见性发生变化。
    setEventOpen(false);
  }
  // 改变本地分页，不请求服务端。
  function changePage(nextPage: number, nextSize: number) {
    // 页大小变化时返回第一页。
    setPage(nextSize === size ? nextPage : 1);
    // 更新本地展示数量。
    setSize(nextSize);
  }
  // 固定接收记录独立于当前表单模式，不伪造验签或业务结果。
  const columns: TableColumnsType<ReceiptSample> = [
    { title: '事件 ID / 接收时间', key: 'event', width: 210, render: (_, record) => <><span className="mono">{record.eventId}</span><span className="cell-secondary">{formatTime(record.receivedAt)}</span></> },
    { title: '响应行为', dataIndex: 'configMode', width: 180, render: (value: SimulatorMode) => modeLabels[value] },
    { title: '计划响应', dataIndex: 'plannedStatus', width: 130, render: (value: number) => <span className="http-code">HTTP {value}</span> },
    { title: '校验 / 幂等', key: 'validation', width: 220, render: (_, record) => <><span>{record.signatureValid ? '验签通过（样例）' : '验签未通过（样例）'}</span><span className="cell-secondary">{record.duplicateProcessed ? '已处理，跳过重复副作用' : '未命中已处理事件'}</span></> },
    { title: '操作', key: 'action', width: 120, render: (_, record) => <Link className="table-link" to={`/events/${encodeURIComponent(record.eventId)}`}>投递详情</Link> },
  ];
  // 参数条件渲染只改变界面，不保存、不投递、不产生假操作成功。
  return <><PageHeading title="故障模拟器" description="在可控的响应中，理解成功、失败与重试。" action={<Button icon={<PlusOutlined />} onClick={() => setEventOpen(true)}>生成测试事件</Button>} /><StaticNotice>仅展示模式配置与固定接收样例。模式切换是未保存的界面草稿；不执行请求、验签、延迟或投递。</StaticNotice><div className="simulator-grid page-section"><section className="surface-panel"><div className="section-heading"><h2>响应行为</h2><span className="subtle">系统固定内部目标</span></div><Form layout="vertical" initialValues={{ failFirstN: 2, delayMs: 8000 }}>
    <fieldset className="mode-options"><legend className="sr-only">选择响应模式</legend><Radio.Group value={mode} onChange={changeMode}>
      {/* 遍历固定模式，只改变选中的参数区和规则文字。 */}
      {modes.map((value) => <Radio className="mode-option" key={value} value={value}><strong>{modeLabels[value]}</strong><small>{modeDescriptions[value]}</small></Radio>)}
    </Radio.Group></fieldset>
    {/* N 只在前 N 次失败模式显示，无关字段不参与当前校验。 */}
    {mode === 'FAIL_FIRST_N' && <Form.Item label="失败次数 N" name="failFirstN" rules={[{ validator: validateN }]} extra="0–10 次；N ≥ 3 时首轮会耗尽次数。"><InputNumber min={0} max={10} precision={0} /></Form.Item>}
    {/* 延迟模式展示毫秒输入，不根据本地值生成 UNKNOWN 尝试。 */}
    {mode === 'PROCESSED_THEN_DELAY' && <Form.Item label="响应延迟（毫秒）" name="delayMs" rules={[{ validator: validateDelay }]} extra="1–30,000 ms。超过接收端超时才可能出现结果未知；默认超时 5,000 ms。"><InputNumber min={1} max={30000} precision={0} /></Form.Item>}
    <div className="form-bottom"><span className="subtle">未保存的界面草稿</span><PendingAction>保存模拟配置</PendingAction></div></Form></section><aside className="surface-panel"><div className="section-heading"><h2>预期投递路径</h2><span className="outline-tag">规则说明</span></div><ol className="behavior-path">
      {/* 规则路径是静态文案，不代表已经执行的尝试记录。 */}
      {paths[mode].map((step) => <li key={step.title}><span className="path-node" /><strong>{step.title}</strong><p>{step.text}</p></li>)}
    </ol><div className="definition-box">自动重试不创建新轮次。<br />每轮最多 3 次，退避间隔 10 秒、30 秒。<br />实际结果只能由后端记录确认。</div><Link className="table-link" to="/events">前往事件管理查看历史</Link></aside></div><section className="event-panel"><div className="table-caption"><strong>模拟接收记录 <span className="subtle">2 条固定样例</span></strong><span className="subtle">计划响应不等于发送方已收到响应</span></div><SampleTable rows={receiptSamples} columns={columns} label="模拟接收记录" page={page} size={size} onPage={changePage} /></section><p className="below-table">接收记录不随草稿或按钮操作改变；不展示签名或密钥。</p>
    {/* 测试入口仅展示选择表单，最终发送禁用，不沿用原型静态结果生成器。 */}
    <PreviewModal title="生成测试事件" open={eventOpen} onClose={closeEvent} action="生成并投递"><p>真实配置读取、保存和创建事件均待接口接入。当前草稿不会用于生成任何结果。</p><Form layout="vertical"><Form.Item label="内置模拟接收端"><Select placeholder="选择接收端" options={simulatorOptions} /></Form.Item></Form><Alert type="warning" showIcon message="此表单不发送 HTTP，不创建事件、轮次、接收记录或操作记录。" /></PreviewModal>
  </>;
}
