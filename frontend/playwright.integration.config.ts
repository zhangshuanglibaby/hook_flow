import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: './tests/integration', workers: 1, retries: 0, reporter: 'list',
  use: { baseURL: process.env.FRONTEND_TEST_URL || 'http://127.0.0.1:5173', trace: 'off', screenshot: 'off', video: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
