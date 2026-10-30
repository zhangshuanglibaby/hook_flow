// 操作记录只读；分页状态不产生任何新审计。
import { useState } from 'react';
import { Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { auditSamples } from '../../data/samples';
import { auditLabels } from '../../data/labels';
import type { AuditSample } from '../../data/types';
import { formatTime } from '../../shared/format';
import { PageHeading, SampleTable, StaticNotice } from '../../shared/ui';

// 使用内部用户与对象 ID，避免捏造后端尚未提供的名称。
export function AuditPage() {
  // 默认第一页，只影响静态样例切片。
  const [page, setPage] = useState(1);
  // 默认 20 条，与正式技术方案一致。
  const [size, setSize] = useState(20);
  // 翻页只浏览现有样例，不查询或追加记录。
  function changePage(nextPage: number, nextSize: number) {
    // 改每页条数时回到第一页。
    setPage(nextSize === size ? nextPage : 1);
    // 保存表格展示数量，不持久化。
    setSize(nextSize);
  }
  // 每列直接展示样例中的承诺字段，未知 action 保留安全原文。
  const columns: TableColumnsType<AuditSample> = [
    { title: '操作人', dataIndex: 'actorUserId', width: 150, render: (id: number) => <div className="actor-cell"><span className="avatar">U</span><span>用户 ID {id}</span></div> },
    { title: '管理操作', dataIndex: 'action', width: 160, render: (action: string) => auditLabels[action] ?? action },
    { title: '关联对象（内部 ID）', key: 'object', width: 220, render: (_, record) => <span className="mono">{record.objectType} / {record.objectId}</span> },
    { title: '操作时间', dataIndex: 'occurredAt', width: 190, render: (value: string) => formatTime(value) },
    { title: '关联请求 ID', dataIndex: 'requestId', width: 200, render: (value: string) => <span className="mono subtle">{value}</span> },
  ];
  // 刷新禁用且说明待接口；不在浏览器合成操作人或记录。
  return <><PageHeading title="操作记录" description="关键管理操作，保留清晰的上下文。" action={<Button icon={<ReloadOutlined />} disabled>刷新记录 · 待接口接入</Button>} /><StaticNotice>全部为预先写定的只读样例，不记录当前浏览器操作。不展示密码、完整事件内容、API Key、签名或密钥。</StaticNotice><section className="event-panel page-section"><div className="table-caption"><strong>操作记录 <span className="subtle">6 条固定样例</span></strong><span className="subtle">最新操作在前 · 只读</span></div><SampleTable rows={auditSamples} columns={columns} label="关键管理操作记录" page={page} size={size} onPage={changePage} /></section><p className="below-table">对象 ID 是内部主键，不作为外部事件 ID 跳转。不提供编辑、删除或导出。</p></>;
}
