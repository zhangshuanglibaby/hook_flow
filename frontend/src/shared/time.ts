// 后端要求时间使用带 Z 的 UTC ISO8601 格式。
const utcPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

// 检查字符串既符合 UTC 格式，又确实能被 Date 解析。
export function validUtc(value: string): boolean {
  return utcPattern.test(value) && Number.isFinite(Date.parse(value));
}

// 把页面上的本地日历日期转换成 UTC 时间。
// 这里使用本地 Date 的午夜，而不是简单拼接“00:00Z”，这样夏令时地区也不会错一天。
export function localDateToUtc(value: string): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('日期格式不正确。');

  const [year, month, day] = value.split('-').map(Number);
  // JavaScript 的月份从0开始，所以要减1。
  const date = new Date(year, month - 1, day);
  // Date 会自动修正 2月30日这类无效日期，因此要反向比较，主动拒绝它。
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    throw new Error('日期无效。');
  }
  return date.toISOString();
}

// 把后端 UTC 时间转换成当前浏览器时区的日期控件值。
export function utcToLocalDate(value?: string): string {
  if (!value) return '';
  const date = new Date(value);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

// 概览默认查询最近7个本地日历日：从6天前的00:00到明天的00:00。
export function defaultRange() {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  from.setDate(from.getDate() - 6);
  const to = new Date();
  to.setHours(0, 0, 0, 0);
  to.setDate(to.getDate() + 1);
  return { from: from.toISOString(), to: to.toISOString() };
}

// 用纳秒精度比较时间，避免 Date.parse 丢掉毫秒以下的小数部分。
function utcNanoseconds(value: string): bigint {
  const [whole, fraction = ''] = value.slice(0, -1).split('.');
  return BigInt(Date.parse(`${whole}Z`)) * 1000000n + BigInt(fraction.padEnd(9, '0'));
}

// 检查后端要求的半开区间 [from, to)：包含起点，不包含终点。
export function rangeError(from?: string, to?: string): string | undefined {
  if ((from && !validUtc(from)) || (to && !validUtc(to))) return '时间必须为 UTC ISO8601（带 Z）。';
  if (from && to && utcNanoseconds(from) >= utcNanoseconds(to)) return '起始时间必须早于结束时间，结束时间不包含。';
}
