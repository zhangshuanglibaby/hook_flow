// 登录后只允许回到本站已经定义的管理页面，防止 returnTo 变成外部跳转地址。
export function safeReturnTo(value?: string): string {
  // 没有值、不是绝对路径、以 // 开头或包含控制字符时，一律回到事件列表。
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\x00-\x20]/.test(value)) return '/events';
  try {
    // 用固定的虚拟 origin 解析路径，方便判断是否偷偷变成了外部 URL。
    const url = new URL(value, 'https://hookflow.invalid');
    const allowedPath = /^\/(?:events(?:\/[^/]+)?|overview|endpoints|simulator|audit-logs)$/;
    if (url.origin !== 'https://hookflow.invalid' || url.pathname === '/login' || !allowedPath.test(url.pathname)) return '/events';
    // 只保留站内 pathname 和筛选 query，不把 hash 或外部 origin 带回页面。
    return `${url.pathname}${url.search}`;
  } catch {
    return '/events';
  }
}

// 后端当前对部分路径保留字符的详情请求会被安全链拒绝。
// 在发起请求前保守阻止，避免把后端安全链的拒绝误显示成会话过期。
export function eventPathSafe(id: string): boolean {
  return !/[\/%\\?#;\x00-\x1f]/.test(id);
}
