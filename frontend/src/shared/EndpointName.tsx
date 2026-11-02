import { useQuery } from '@tanstack/react-query';
import { api } from './api/resources';
import { useSessionKey } from './query';

export function EndpointName({ id }: { id: number }) {
  const generation = useSessionKey();
  const query = useQuery({ queryKey: [generation, 'endpoints', 'detail', id], queryFn: ({ signal }) => api.endpoint(id, signal) });
  return <><span>{query.data?.name ?? `接收端 ID ${id}`}</span><span className="cell-secondary">{query.data ? `${query.data.targetType === 'SIMULATOR' ? '模拟接收端' : '外部 HTTPS'}${query.data.enabled ? '' : ' · 已停用'}` : query.isError ? '名称加载失败' : '正在读取名称'}</span></>;
}
