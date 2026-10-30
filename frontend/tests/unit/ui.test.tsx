// 组件测试只传静态 props，不挂载请求型逻辑或响应拦截。
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DisplayState, PendingAction, StatusTag } from '../../src/shared/ui';

// 验证公共展示组件保留任务与尝试的语义边界。
describe('公共静态展示组件', () => {
  // 状态始终具有中文，不只依赖颜色。
  it('以可见文字展示暂停状态', () => {
    // 直接传入只读状态，不生成任务。
    render(<StatusTag status="PAUSED" />);
    // 暂停文案必须出现在 DOM。
    expect(screen.getByText('暂停中')).toBeVisible();
  });
  // 未知枚举不能被默认映射成成功。
  it('保守展示未知状态', () => {
    // 未识别的值只作为文字，不解释为第七种已支持任务状态。
    render(<StatusTag status="UNRECOGNIZED" />);
    // 使用用户可见的未知状态说明。
    expect(screen.getByText('未知状态：UNRECOGNIZED')).toBeVisible();
  });
  // 正式业务最终按钮必须禁用。
  it('禁用业务操作且提供可见待接入说明', () => {
    // 渲染与真实页面相同的最终操作包装。
    render(<PendingAction>保存</PendingAction>);
    // 断言按钮不可点击，而非只在点击后提示成功。
    expect(screen.getByRole('button', { name: '保存 · 待接口接入' })).toBeDisabled();
  });
  // 加载不展示空数据或假 0 条。
  it('独立展示加载样式', () => {
    // 状态来自 props，不启动任何请求或计时器。
    render(<DisplayState kind="loading" />);
    // 读屏状态应明确忙碌。
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    // 加载不是没有数据。
    expect(screen.queryByText('暂无数据')).not.toBeInTheDocument();
  });
  // 错误预览必须明确非真实请求错误。
  it('错误预览不伪装真实请求', () => {
    // 直接展示错误组件，不拦截任何 HTTP。
    render(<DisplayState kind="error" />);
    // 未发请求的事实应对用户可见。
    expect(screen.getByText(/未执行真实请求/)).toBeVisible();
    // 重载不能伪造成功，因此保持禁用。
    expect(screen.getByRole('button', { name: '重新加载 · 待接口接入' })).toBeDisabled();
  });
});
