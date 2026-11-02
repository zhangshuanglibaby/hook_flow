import { expect, test } from '@playwright/test';

const username = process.env.HOOKFLOW_TEST_USERNAME;
const password = process.env.HOOKFLOW_TEST_PASSWORD;
test.skip(!username || !password, '需从安全环境注入专用开发管理员凭据；不使用固定密码或模拟认证。');
test('真实登录、管理查询与退出撤销', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(username!);
  await page.getByLabel('密码', { exact: true }).fill(password!);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/events$/);
  await expect(page.getByRole('heading', { name: '事件管理', exact: true })).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(/\/events$/);
  await expect(page.getByRole('heading', { name: '事件管理', exact: true })).toBeVisible();
  for (const [path, heading] of [['/overview', '概览'], ['/endpoints', '接收端管理'], ['/simulator', '故障模拟器'], ['/audit-logs', '操作记录']]) {
    await page.getByRole('navigation').getByRole('link', { name: heading, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
    await expect(page.getByText('请求未完成', { exact: true })).toHaveCount(0);
  }
  expect(await page.evaluate(() => ({
    local: localStorage.length,
    hasSession: sessionStorage.getItem('hookflow.admin-session.v1') !== null,
    hasPassword: Object.hasOwn(JSON.parse(sessionStorage.getItem('hookflow.admin-session.v1') ?? '{}'), 'password'),
  }))).toEqual({ local: 0, hasSession: true, hasPassword: false });
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await page.getByRole('button', { name: '退出并撤销会话', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => sessionStorage.getItem('hookflow.admin-session.v1'))).toBeNull();
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
});
