// 这些工具只校验本地输入格式，不发送请求，也不执行正式业务。

// 检查必填文本的 Unicode 字符数；密码不去空格，避免悄悄改变用户输入。
export function textError(value: string, max: number): string | undefined {
  // 使用展开语法按 Unicode 码点计数，比字符串 length 更接近用户理解的字符数。
  const count = [...value].length;
  // 空文本或超长时返回就地错误；有效时不返回错误。
  return count < 1 || count > max ? `请输入 1–${max} 个字符。` : undefined;
}
// 检查任意 JSON 值的语法；不把解析结果写回输入，避免格式化损失数字精度。
export function jsonError(value: string): string | undefined {
  // 空文本单独说明，合法的 null 不应误判为空。
  if (!value.trim()) return '请输入事件 JSON 内容，null 也是合法值。';
  // 捕获 JSON 语法错误，避免错误输入中断页面渲染。
  try {
    // 这里只检查语法，不保存解析后的数字，更不会构造请求。
    JSON.parse(value);
    // 成功解析表示格式有效，但不代表通过正式提交校验。
    return undefined;
  } catch {
    // 给初学者可操作的格式修正提示。
    return 'JSON 格式不正确，请检查双引号、逗号和括号。';
  }
}
// 校验公开 HTTPS URL 的基本格式；DNS、白名单和实际连接 IP 仍须后端验证。
export function urlError(value: string): string | undefined {
  // 无法解析的 URL 在 catch 中给统一输入提示。
  try {
    // URL 对象只用于读取协议和主机，不发起网络请求。
    const url = new URL(value);
    // 转小写后比较主机名，避免大小写绕过本地提示。
    const host = url.hostname.toLowerCase();
    // 拒绝非 HTTPS、凭据和片段，以符合当前外部接收端格式约束。
    if (url.protocol !== 'https:' || url.username || url.password || url.hash) return '请输入不含凭据或片段的 HTTPS URL。';
    // 拒绝内部名称与 IP 字面量；这只是输入体验，不是 SSRF 防护。
    if (!host.includes('.') || host.endsWith('.localhost') || host.endsWith('.local') || host.startsWith('[') || /^\d+(\.\d+){3}$/.test(host)) return '请填写公开域名，不使用 localhost、内部域名或 IP 地址。';
    // 域名格式可用时不显示错误。
    return undefined;
  } catch {
    // URL 解析失败时显示完整示例。
    return '请输入完整 URL，例如 https://hooks.example.com/events。';
  }
}
// 检查参数的整数范围，供超时、N 和延迟输入复用。
export function integerError(value: number | null, min: number, max: number): string | undefined {
  // null、非整数和越界分别都属于不合法输入。
  return value === null || !Number.isInteger(value) || value < min || value > max ? `请输入 ${min}–${max.toLocaleString('zh-CN')} 的整数。` : undefined;
}
// 将已知 ISO 时间展示为浏览器本地时间，不改变静态样例的原始时间。
export function formatTime(value: string | null): string {
  // 没有尝试时间时显示“尚未开始”，而非无意义的 Invalid Date。
  if (value === null) return '尚未开始';
  // 使用系统本地时区并固定中文 24 小时制显示。
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
