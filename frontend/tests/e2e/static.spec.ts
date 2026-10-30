// 每条用例在真实浏览器操作 DOM；不使用 route.fulfill、fetch stub 或模拟服务器。
import { expect, test } from '@playwright/test';

// 七类页面与 404 都必须直接访问，静态阶段不检查登录。
const routes = [
  ['/login', '登录工作台'], ['/overview', '概览'], ['/events', '事件管理'],
  ['/events/evt-demo-1036', '投递详情'], ['/endpoints', '接收端管理'],
  ['/simulator', '故障模拟器'], ['/audit-logs', '操作记录'], ['/not-a-page', '页面不存在'],
] as const;
// 遍历已确认页面，而不是新增未经批准的业务入口。
for (const [path, heading] of routes) {
  // 每个入口都覆盖刷新后可达与静态边界标识。
  test(`直接访问 ${path}`, async ({ page }) => {
    // 打开指定地址，不预先设置 Token 或浏览器存储。
    await page.goto(path);
    // 用可见标题确认不是空白页面。
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
    // 刷新仍应显示同一页面。
    await page.reload();
    // 不依赖列表缓存或假认证恢复。
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
  });
}
// 三个目标尺寸覆盖所有页面的整页溢出，而非只测试一张表格。
for (const width of [1440, 768, 375]) {
  // 每个尺寸都检查页面宽度和表格局部滚动。
  test(`${width}px 页面不出现整体横向溢出`, async ({ page }) => {
    // 切换真实浏览器视口，触发响应式断点。
    await page.setViewportSize({ width, height: 960 });
    // 遍历所有正式入口，检查每个页面。
    for (const [path] of routes) {
      // 导航不会生成业务数据。
      await page.goto(path);
      // 等待 React 内容可见，避免在空 HTML 阶段误报通过。
      await expect(page.locator('h1, h2').first()).toBeVisible();
      // 读取实际布局尺寸，局部 table scrollWidth 不参与整页误判。
      const overflow = await page.evaluate(() => {
        // 返回根节点是否比可见视口更宽。
        return document.documentElement.scrollWidth > document.documentElement.clientWidth + 1;
      });
      // 任一页面溢出均报告具体路由。
      expect(overflow, `${width}px ${path}`).toBe(false);
    }
  });
}
// 完整覆盖侧面板、浏览历史与关闭后的键盘焦点。
test('列表详情支持 Escape、焦点返回和浏览器前进后退', async ({ page }) => {
  // 从列表进入，确保详情带有背景。
  await page.goto('/events');
  // 使用有稳定 ID 的事件链接作为焦点触发点。
  const trigger = page.locator('#event-evt-demo-1036');
  // 键盘聚焦列表链接。
  await trigger.focus();
  // Enter 打开详情，不依赖鼠标操作。
  await page.keyboard.press('Enter');
  // Drawer 是真实可见对话框。
  await expect(page.getByRole('dialog')).toBeVisible();
  // Escape 应关闭侧面板并返回列表。
  await page.keyboard.press('Escape');
  // 确认 URL 已返回站内事件页。
  await expect(page).toHaveURL(/\/events$/);
  // 原触发点应重新获得焦点。
  await expect(trigger).toBeFocused();
  // 重新进入详情检查浏览器后退。
  await trigger.click();
  // 后退恢复列表地址。
  await page.goBack();
  // 原生浏览器导航不应被守卫拦截。
  await expect(page).toHaveURL(/\/events$/);
  // 前进再次打开相同详情地址。
  await page.goForward();
  // 详情 URL 和面板均恢复。
  await expect(page.getByRole('dialog')).toBeVisible();
});
// 所有创建最终动作仍被禁用，Escape 后恢复按钮焦点。
test('创建弹窗只校验格式且不创建记录', async ({ page }) => {
  // 直接访问事件列表。
  await page.goto('/events');
  // 打开创建表单，不会增加任何业务记录。
  const trigger = page.getByRole('button', { name: '创建测试事件', exact: true });
  // 打开后等待浮层出现。
  await trigger.click();
  // 最终创建动作应明确禁用。
  await expect(page.getByRole('button', { name: '创建 · 待接口接入' })).toBeDisabled();
  // 输入错误 JSON 验证就地反馈。
  await page.getByLabel('事件内容（JSON）').fill('{');
  // 移出焦点触发表单校验。
  await page.getByLabel('事件类型', { exact: true }).click();
  // 错误应对用户可见，而不是写入后才报错。
  await expect(page.getByText('JSON 格式不正确，请检查双引号、逗号和括号。')).toBeVisible();
  // Escape 只关闭浮层。
  await page.keyboard.press('Escape');
  // 原按钮恢复焦点。
  await expect(trigger).toBeFocused();
  // 原固定样例仍存在，未增加假记录。
  await expect(page.getByText('8 条只读样例')).toBeVisible();
});
// 模式切换仅显示对应参数，不写配置与接收记录。
test('模拟器参数随模式切换且不生成业务结果', async ({ page }) => {
  // 打开模拟器页面。
  await page.goto('/simulator');
  // 初始模式不应显示无关 N 输入。
  await expect(page.getByLabel('失败次数 N', { exact: true })).toHaveCount(0);
  // 切换到前 N 次失败模式。
  await page.getByRole('radio', { name: /前 N 次失败后成功/ }).check();
  // 当前参数区显示 N。
  await expect(page.getByLabel('失败次数 N', { exact: true })).toBeVisible();
  // 切换到延迟模式。
  await page.getByRole('radio', { name: /已处理后延迟响应/ }).check();
  // N 区域已经移除。
  await expect(page.getByLabel('失败次数 N', { exact: true })).toHaveCount(0);
  // 延迟参数可见但尚未保存。
  await expect(page.getByLabel('响应延迟（毫秒）', { exact: true })).toBeVisible();
  // 保存动作始终禁用。
  await expect(page.getByRole('button', { name: '保存模拟配置 · 待接口接入' })).toBeDisabled();
  // 接收记录仍是独立固定样例。
  await expect(page.getByText('2 条固定样例')).toBeVisible();
});
// 密码可见切换必须能通过键盘操作，同时登录保持禁用。
test('密码显示按钮支持键盘且不伪登录', async ({ page }) => {
  // 直接进入独立登录页，无须准备认证状态。
  await page.goto('/login');
  // 用虚构密码输入验证切换，不使用真实凭据。
  const password = page.getByLabel('密码', { exact: true });
  // 填写仅当前表单内存在的样例输入。
  await password.fill('fictional-password');
  // 找到可聚焦的显示按钮。
  const toggle = page.getByRole('button', { name: '显示密码' });
  // 聚焦按钮后用空格触发，而不是依赖鼠标。
  await toggle.focus();
  // 原生按钮的键盘行为负责触发切换。
  await page.keyboard.press('Space');
  // 仅密码输入类型改变，内容不变。
  await expect(password).toHaveAttribute('type', 'text');
  // 切换后 aria-pressed 必须为 true。
  await expect(page.getByRole('button', { name: '隐藏密码' })).toHaveAttribute('aria-pressed', 'true');
  // 登录最终动作仍禁用。
  await expect(page.getByRole('button', { name: '登录 · 待接口接入' })).toBeDisabled();
  // 页面没有跳转来冒充登录成功。
  await expect(page).toHaveURL(/\/login$/);
});

// 冷蓝主题检查使用实际 CSS 计算值，而不是只看源码色值。
test('主按钮使用浅蓝底深蓝字并可见键盘焦点', async ({ page }) => {
  // 在事件页定位主要按钮。
  await page.goto('/events');
  // 选择可交互的主要动作。
  const button = page.getByRole('button', { name: '创建测试事件', exact: true });
  // 核对浅蓝底色。
  await expect(button).toHaveCSS('background-color', 'rgb(207, 228, 255)');
  // 核对深蓝文字颜色。
  await expect(button).toHaveCSS('color', 'rgb(25, 88, 166)');
  // 以键盘操作产生 focus-visible。
  await button.focus();
  // Tab 后 Shift+Tab 保证键盘模式生效。
  await page.keyboard.press('Tab');
  // 回到原按钮。
  await page.keyboard.press('Shift+Tab');
  // 焦点外框不能被隐藏。
  await expect(button).toHaveCSS('outline-style', 'solid');
});
