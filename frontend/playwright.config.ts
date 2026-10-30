// Playwright 用于真实浏览器检查，不设置接口响应替身。
import { defineConfig, devices } from '@playwright/test';

// 连接另行启动的 Vite；测试本身不偷偷启动另一个服务器。
export default defineConfig({
  // 浏览器测试与单测分目录运行。
  testDir: './tests/e2e',
  // 单机演示工程顺序执行，便于复查焦点与截图。
  workers: 1,
  // 保留清晰的终端结果与可查看的 HTML 报告。
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    // 必须先在本项目启动 5173 开发服务器。
    baseURL: 'http://127.0.0.1:5173',
    // 检查失败时保存屏幕和操作轨迹，成功时不堆积产物。
    screenshot: 'only-on-failure', trace: 'retain-on-failure',
  },
  // 只安装本轮实际使用的 Chromium，不提前增加其他浏览器依赖。
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
