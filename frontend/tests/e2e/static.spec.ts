import { expect, test } from '@playwright/test';

for (const route of ['/events', '/events/direct-event-id', '/overview', '/endpoints', '/simulator', '/audit-logs']) {
  test(`未登录保护 ${route}`, async ({ page }) => {
    await page.goto(route);
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
    await expect(page.getByText('浏览静态页面（非登录成功）')).toHaveCount(0);
  });
}
for (const width of [1440, 768, 375]) {
  test(`登录页 ${width}px 无整页溢出`, async ({ page }) => {
    await page.setViewportSize({ width, height: 960 });
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true);
  });
}
test('开发环境默认填写演示账号，不自动发送登录请求', async ({ page }) => {
  let requests = 0;
  page.on('request', (request) => { if (request.url().endsWith('/auth/login')) requests += 1; });
  await page.goto('/login');
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('admin');
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('123456');
  expect(requests).toBe(0);
});
test('登录必填校验，不发送空请求', async ({ page }) => {
  let requests = 0;
  page.on('request', (request) => { if (request.url().endsWith('/auth/login')) requests += 1; });
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('');
  await page.getByLabel('密码', { exact: true }).fill('');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText('请输入 1–64 个字符。')).toBeVisible();
  expect(requests).toBe(0);
});
test('密码切换与冷蓝按钮', async ({ page }) => {
  await page.goto('/login');
  const password = page.getByLabel('密码', { exact: true });
  await password.fill('fictional-test-input');
  const toggle = page.getByRole('button', { name: '显示密码' });
  await toggle.focus(); await page.keyboard.press('Space');
  await expect(password).toHaveAttribute('type', 'text');
  const button = page.getByRole('button', { name: '登录', exact: true });
  await expect(button).toHaveCSS('background-color', 'rgb(207, 228, 255)');
  await expect(button).toHaveCSS('color', 'rgb(25, 88, 166)');
});
