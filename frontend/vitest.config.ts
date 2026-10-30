// 测试配置独立于生产构建，只运行本地纯函数和展示组件测试。
import { defineConfig } from 'vitest/config';
// React 插件让测试能编译 TSX 组件。
import react from '@vitejs/plugin-react';

// 不设置网络 stub、模拟接口或服务端响应。
export default defineConfig({
  // 使用相同的 React 编译方式。
  plugins: [react()],
  test: {
    // jsdom 提供组件测试所需 DOM，不代表浏览器实测。
    environment: 'jsdom',
    // 单测与浏览器测试分开，避免 Playwright 测试被重复执行。
    include: ['tests/unit/**/*.test.{ts,tsx}'],
    // 每个测试前加载 DOM 断言扩展与组件清理。
    setupFiles: ['./tests/unit/setup.ts'],
    // 避免测试间残留监视器或 mock 状态。
    restoreMocks: true,
  },
});
