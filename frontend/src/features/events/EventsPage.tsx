// 页面状态只控制弹窗是否可见，不保存事件或生成业务结果。
import { useState } from 'react';
import { Button, Form, Input, Select } from 'antd';
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { Link, useLocation } from 'react-router-dom';
import { endpointSamples, eventSamples, payloadSample } from '../../data/samples';
import { statusLabels } from '../../data/labels';
import type { EventSample } from '../../data/types';
import { formatTime, jsonError, textError } from '../../shared/format';
import { PageHeading, PreviewModal, SampleTable, StaticNotice, StatusTag } from '../../shared/ui';

// 创建表单只做输入和语法校验，确认按钮始终禁用。
function CreateEventForm() {
  // JSON validator 将纯格式校验结果交给 Ant Design，就地展示错误。
  async function validateJson(_: unknown, value: string) {
    // 将空值统一成空字符串，让空内容也得到明确提示。
    const error = jsonError(value ?? '');
    // 仅在有错误时拒绝校验，不发请求或改写内容。
    if (error) throw new Error(error);
  }
  // 文本校验只约束事件类型字符数，不生成平台 UUID。
  async function validateType(_: unknown, value: string) {
    // 类型去掉首尾空格后检查，不影响密码等其他输入。
    const error = textError((value ?? '').trim(), 128);
    // 让组件库关联错误和当前字段，便于键盘及读屏定位。
    if (error) throw new Error(error);
  }
  // 表单不绑定 onFinish，按 Enter 也不会执行假创建。
  return <Form layout="vertical" initialValues={{ payload: payloadSample }}>
    <p className="subtle">事件 ID 将由后端生成；本轮不生成 ID、不新增记录。请勿输入真实个人信息。</p>
    <Form.Item label="事件类型" name="eventType" rules={[{ validator: validateType }]}><Input placeholder="如 order.paid" /></Form.Item>
    <Form.Item label="接收端" name="endpointId" rules={[{ required: true, message: '请选择启用的接收端。' }]}>
      {/* 只把集中样例转换成选择项；停用项不能用于新建。 */}
      <Select placeholder="选择接收端" options={endpointSamples.map((endpoint) => ({ value: endpoint.id, label: `${endpoint.name}${endpoint.enabled ? '' : '（已停用）'}`, disabled: !endpoint.enabled }))} />
    </Form.Item>
    <Form.Item label="事件内容（JSON）" name="payload" rules={[{ validator: validateJson }]} extra="接受对象、数组、字符串、数字、布尔和 null。仅检查语法，不格式化或提交。"><Input.TextArea rows={8} spellCheck={false} className="mono" /></Form.Item>
  </Form>;
}
// 事件列表保留原型查询、状态标签、轮次与最近时间层级；不计算假业务状态。
export function EventsPage() {
  // 保存弹窗开关，关闭时不改变集中只读样例。
  const [createOpen, setCreateOpen] = useState(false);
  // 仅保存表格当前展示页码，默认每页 20 条。
  const [page, setPage] = useState(1);
  // 每页数量只影响本地分页呈现，不代表接口已经支持查询。
  const [size, setSize] = useState(20);
  // 创建 Ant Design 表单实例，使“重置”能同步重置受控字段而不只是原生 DOM。
  const [filterForm] = Form.useForm();
  // 当前 location 用作详情侧面板背景，关闭后保留列表上下文。
  const location = useLocation();
  // 读取概览传来的定位条件，仅供表单显示，不对固定样例伪执行查询。
  const params = new URLSearchParams(location.search);
  // 为表格附加稳定行键，保留原始数据不变。
  const rows = eventSamples.map((event) => ({ ...event, id: event.eventId }));
  // 列定义明确每项字段的来源，不从不同列表按索引拼接。
  const columns: TableColumnsType<EventSample & { id: string }> = [
    { title: '事件 ID / 类型', key: 'event', width: 205, render: (_, event) => (
      // 路由 state 保存背景和触发链接 ID，返回时恢复焦点。
      <><Link id={`event-${event.eventId}`} className="event-id mono" to={`/events/${encodeURIComponent(event.eventId)}`} state={{ background: location, triggerId: `event-${event.eventId}` }}>{event.eventId}</Link><span className="cell-secondary mono">{event.eventType}</span></>
    ) },
    { title: '接收端', key: 'endpoint', width: 165, render: (_, event) => {
      // 用静态接收端 ID 找显示名称，不合成或更新接收端状态。
      const endpoint = endpointSamples.find((item) => item.id === event.endpointId);
      // 未找到时保留 ID 说明，不能误显示另一个接收端。
      return <><span>{endpoint?.name ?? `接收端 ${event.endpointId}`}</span><span className="cell-secondary">{endpoint?.targetType === 'SIMULATOR' ? '模拟接收端' : endpoint?.enabled ? '外部 HTTPS' : '已停用'}</span></>;
    } },
    { title: '投递状态', dataIndex: 'status', width: 135, render: (status: string) => <StatusTag status={status} /> },
    { title: '轮次 / 尝试', key: 'round', width: 145, render: (_, event) => <><span>第 {event.latestRoundNo} 轮</span><span className="cell-secondary">{event.attemptCount} / {event.maxAttempts} 次尝试</span></> },
    { title: '创建时间', dataIndex: 'createdAt', width: 175, render: (value: string) => <span className="date-cell">{formatTime(value)}</span> },
    { title: '最近投递时间', dataIndex: 'lastAttemptAt', width: 175, render: (value: string | null) => <span className="date-cell">{formatTime(value)}</span> },
    { title: '操作', key: 'action', width: 115, render: (_, event) => <Link id={`detail-${event.eventId}`} className="table-link" to={`/events/${encodeURIComponent(event.eventId)}`} state={{ background: location, triggerId: `detail-${event.eventId}` }} aria-label={`查看 ${event.eventId} 投递详情`}>查看详情</Link> },
  ];
  // 打开创建表单只改变浮层状态，不写业务数据。
  function openCreate() {
    // 显示弹窗，最终创建按钮仍处于禁用状态。
    setCreateOpen(true);
  }
  // 重置只清空本地筛选输入，不执行查询或改变固定事件样例。
  function resetFilters() {
    // 恢复表单初始字段值，若从概览进入则保留原定位条件。
    filterForm.resetFields();
  }
  // 关闭弹窗不会创建或保存任何事件。
  function closeCreate() {
    // 隐藏浮层后由 Ant Design 将焦点返回触发按钮。
    setCreateOpen(false);
  }
  // 翻页只浏览预先声明的样例，不触发查询或刷新。
  function changePage(nextPage: number, nextSize: number) {
    // 每页数量改变时回到第一页，避免显示超出范围的页码。
    setPage(nextSize === size ? nextPage : 1);
    // 保存新的本地展示页大小。
    setSize(nextSize);
  }
  // 过滤区保留设计，但所有需要后端查询的控件动作明确禁用。
  return <><PageHeading title="事件管理" description="追踪每一条事件，从提交到最终响应。" action={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建测试事件</Button>} /><StaticNotice />
    <section className="event-panel"><div className="list-top"><div className="quick-filters"><span className="quick-active">全部事件</span><Button type="text" disabled>最终失败</Button><Button type="text" disabled>等待重试</Button></div><Button icon={<ReloadOutlined />} disabled>刷新列表</Button></div>
      <Form form={filterForm} className="filter-form" layout="vertical" initialValues={{ status: params.get('status') ?? undefined, from: params.get('from') ?? '', to: params.get('to') ?? '' }}>
        <div className="filter-primary"><Form.Item label="事件 ID" name="eventId"><Input prefix={<SearchOutlined />} placeholder="输入完整事件 ID" /></Form.Item><Form.Item label="事件类型" name="eventType"><Input placeholder="如 order.paid" /></Form.Item><Form.Item label="接收端" name="endpointId">
          {/* 选项只负责展示本地接收端，不伪装为远程搜索。 */}
          <Select allowClear placeholder="全部接收端" options={endpointSamples.map((item) => ({ value: item.id, label: item.name }))} />
        </Form.Item><Form.Item label="投递状态" name="status">
          {/* 枚举转换为中文下拉项，UNKNOWN 不会成为任务状态选项。 */}
          <Select allowClear placeholder="全部状态" options={Object.entries(statusLabels).map(([value, label]) => ({ value, label }))} />
        </Form.Item></div><div className="filter-secondary"><div className="date-group"><span>创建时间</span><Form.Item name="from"><Input type="date" aria-label="创建时间起始日期" /></Form.Item><span>至</span><Form.Item name="to"><Input type="date" aria-label="创建时间结束日期，不包含该日" /></Form.Item><span className="subtle">结束日不包含</span></div><div className="filter-actions"><Button onClick={resetFilters}>重置</Button><Button icon={<SearchOutlined />} disabled>查询 · 待接口接入</Button></div></div>
      </Form><div className="table-caption"><strong>事件列表 <span className="subtle">8 条只读样例</span></strong><span className="subtle">状态与尝试次数均以最新一轮为准</span></div>
      {/* 表格翻页只更新 page/size，不过滤、增加或修改记录。 */}
      <SampleTable rows={rows} columns={columns} label="事件列表" page={page} size={size} onPage={changePage} />
    </section><p className="below-table">成功仅表示接收端确认接收，不代表业务处理完成。最终失败表示未确认成功，不代表对方一定未处理。</p><p className="subtle">筛选、快捷状态和刷新待接口接入；当前输入不会改变固定样例。</p>
    {/* 弹窗关闭只切换可见状态，所有正式写入保持禁用。 */}
    <PreviewModal title="创建测试事件" open={createOpen} onClose={closeCreate} action="创建"><CreateEventForm /></PreviewModal>
  </>;
}
