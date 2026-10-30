// 导入 DOM 匹配器，测试可以直接断言可见性与禁用状态。
import '@testing-library/jest-dom/vitest';
// cleanup 卸载上一测试组件，防止界面残留影响下一测试。
import { cleanup } from '@testing-library/react';
// Vitest 的生命周期函数在每项测试结束后运行清理。
import { afterEach } from 'vitest';

// 注册每项测试后的 DOM 清理，不提供任何接口替身。
afterEach(() => {
  // 从 jsdom 卸载当前组件树并解除监听。
  cleanup();
});
