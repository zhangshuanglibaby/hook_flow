import { useState } from 'react';
import { Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../shared/api/resources';
import type { Audit } from '../../shared/api/schemas';
import { auditLabels } from '../../data/labels';
import { formatTime } from '../../shared/format';
import { useSessionKey } from '../../shared/query';
import { ErrorNotice, LoadingState, PageHeading, ServerTable } from '../../shared/ui';

export function AuditPage() {
  // 审计记录是服务端只读数据，页面不创建或修改审计条目。
  const generation = useSessionKey();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  // page 和 size 进入 query key，切页时读取对应的服务端分页。
  const query = useQuery({ queryKey: [generation, 'audit', page, size], queryFn: ({ signal }) => api.audit(page, size, signal) });
  const columns: TableColumnsType<Audit> = [
    { title: '操作人', dataIndex: 'actorUserId', width: 150, render: (id: number) => <div className="actor-cell"><span className="avatar">U</span><span>用户 ID {id}</span></div> },
    { title: '管理操作', dataIndex: 'action', width: 160, render: (action: string) => Object.hasOwn(auditLabels, action) ? auditLabels[action] : action },
    { title: '关联对象（内部 ID）', width: 220, render: (_, record) => <span className="mono">{record.objectType} / {record.objectId}</span> },
    { title: '操作时间', dataIndex: 'occurredAt', width: 190, render: (value: string) => formatTime(value) },
    { title: '关联请求 ID', dataIndex: 'requestId', width: 220, render: (value: string) => <span className="mono subtle">{value}</span> },
  ];
  return <><PageHeading title="操作记录" description="查看关键管理操作与服务端关联请求。" action={<Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => void query.refetch()}>刷新记录</Button>} /><section className="event-panel page-section"><div className="table-caption"><strong>操作记录</strong><span className="subtle">最新操作在前 · 只读</span></div><ErrorNotice error={query.error} stale={Boolean(query.data)} retry={() => void query.refetch()} />{query.isPending ? <LoadingState /> : query.data && <ServerTable data={query.data} columns={columns} label="关键管理操作记录" loading={query.isFetching} onPage={(next, count) => { setPage(next); setSize(count); }} />}</section><p className="below-table">用户与对象名称未由接口提供，显示内部 ID。对象 ID 不等于外部事件 ID，不作为详情链接。记录由服务端生成；登录、测试事件与查询不一定产生管理审计。</p></>;
}
