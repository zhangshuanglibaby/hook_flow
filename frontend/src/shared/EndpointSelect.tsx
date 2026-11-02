import { useState } from 'react';
import { Button, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { api } from './api/resources';
import { useSessionKey } from './query';
import { ErrorNotice } from './ui';

// 接收端选择器只加载当前分页，不假装后端支持搜索。
export function EndpointSelect({ id, value, onChange, creation = false, simulator = false }: { id?: string; value?: number; onChange?: (value: number | undefined) => void; creation?: boolean; simulator?: boolean }) {
  const generation = useSessionKey();
  const [page, setPage] = useState(1);
  // page 进入 query key，翻页时会触发真实的下一页请求。
  const query = useQuery({
    queryKey: [generation, 'endpoints', 'selector', page],
    queryFn: ({ signal }) => api.endpoints(page, 20, signal),
  });
  // 当前选择值可能来自另一页，因此单独记住它的显示文本。
  const [selected, setSelected] = useState<{ value: number; label: string }>();
  const options = query.data?.items
    .filter((item) => !simulator || item.targetType === 'SIMULATOR')
    .map((item) => ({
      value: item.id,
      label: `${item.name}（ID ${item.id}）${!item.enabled ? ' · 已停用' : ''}`,
      // 创建新事件时，停用接收端只能展示，不能选择。
      disabled: creation && !item.enabled,
    })) ?? [];

  // 如果表单已经有值但该值不在当前页，仍保留一个只读显示项，避免页面显示为空。
  if (value && !options.some((item) => item.value === value)) {
    options.unshift({ value, label: selected?.value === value ? selected.label : `接收端 ID ${value}`, disabled: false });
  }

  return <div className="endpoint-select">
    <Select
      id={id}
      allowClear
      value={value}
      onChange={(nextId) => {
        const option = options.find((item) => item.value === nextId);
        setSelected(option);
        onChange?.(nextId);
      }}
      placeholder={simulator ? '选择启用的内置模拟接收端' : '选择接收端'}
      loading={query.isFetching}
      options={options}
      notFoundContent={query.isPending ? '正在加载…' : query.isError ? '加载失败' : '本页无可选接收端'}
      style={{ width: '100%' }}
      // 翻页只属于下拉选项，不占用筛选表单空间；不足一页时不显示。
      popupRender={(menu) => <>
        {menu}
        {(page > 1 || (query.data?.total ?? 0) > 20) && <div
          className="selector-pagination"
          role="group"
          aria-label="接收端选项分页"
          // 鼠标操作翻页时保留输入框焦点，避免下拉面板提前关闭。
          onMouseDown={(event) => event.preventDefault()}
        >
          <Button size="small" disabled={page === 1 || query.isFetching} onClick={() => setPage(page - 1)}>上一页</Button>
          <span>第 {page} 页 · 每页 20 条</span>
          <Button size="small" disabled={!query.data || page * 20 >= query.data.total || query.isFetching} onClick={() => setPage(page + 1)}>下一页</Button>
        </div>}
      </>}
    />
    <ErrorNotice error={query.error} retry={() => void query.refetch()} />
  </div>;
}
