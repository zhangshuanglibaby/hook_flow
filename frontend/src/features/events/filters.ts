import { statusLabels } from '../../data/labels';
import { rangeError } from '../../shared/time';

// 事件列表筛选条件。page 和 size 一定存在，其余条件都是可选的。
export type EventFilters = {
  page: number;
  size: number;
  eventId?: string;
  eventType?: string;
  status?: string;
  endpointId?: number;
  from?: string;
  to?: string;
};

// 从 URL 读取筛选条件。
// URL 是可分享、可刷新恢复的“已提交筛选”，不是表单输入草稿。
export function parseEventFilters(params: URLSearchParams): { filters: EventFilters; error?: string } {
  let error: string | undefined;

  // 统一解析分页和数字 ID，严格拒绝前导零、负数、小数和指数写法。
  function integer(key: string, fallback: number, max: number) {
    const value = params.get(key);
    if (value === null) return fallback;
    const number = Number(value);
    if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(number) || number > max) {
      error = `${key} 参数无效，请重置筛选。`;
      return fallback;
    }
    return number;
  }

  // 列表默认第一页、每页20条；后端允许的最大页大小是100。
  const filters: EventFilters = {
    page: integer('page', 1, 2147483647),
    size: integer('size', 20, 100),
  };

  // 文本筛选按完整值匹配，不能把它们当作模糊搜索。
  for (const key of ['eventId', 'eventType', 'status', 'from', 'to'] as const) {
    const value = params.get(key);
    if (value !== null && value !== '') filters[key] = value;
  }

  // 接收端 ID 是正整数，并且必须保持安全整数精度。
  if (params.has('endpointId')) filters.endpointId = integer('endpointId', 1, Number.MAX_SAFE_INTEGER);

  // UNKNOWN 是尝试结果，不是事件任务状态，不能作为列表 status 筛选。
  if (filters.status && !Object.hasOwn(statusLabels, filters.status)) error = '未知状态筛选，请重置筛选。';
  if ([filters.eventId, filters.eventType].some((value) => value !== undefined && (!value.trim() || [...value].length > 128))) {
    error = '事件 ID 或类型长度无效。';
  }

  // 时间筛选最终也要满足 UTC 格式和 [from,to) 半开区间规则。
  error = rangeError(filters.from, filters.to) ?? error;
  return { filters, error };
}
