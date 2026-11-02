import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ErrorNotice, InfoNotice, LoadingState, StatusTag, WarningNotice } from '../../src/shared/ui';
import { ApiError } from '../../src/shared/api/client';

describe('公共展示语义', () => {
  it('警告提示使用黄色语义和空心三角图标', () => {
    const { container } = render(<WarningNotice message="请先检查历史记录。" />);
    expect(screen.getByText('请先检查历史记录。')).toBeVisible();
    expect(container.querySelector('.ant-alert')).toHaveClass('ant-alert-warning');
    expect(container.querySelector('[data-icon="warning"]')).toBeInTheDocument();
  });
  it('信息提示使用蓝色语义和空心信息图标', () => {
    const { container } = render(<InfoNotice message="接收端已确认接收。" />);
    expect(screen.getByText('接收端已确认接收。')).toBeVisible();
    expect(container.querySelector('.ant-alert')).toHaveClass('ant-alert-info');
    expect(container.querySelector('[data-icon="info-circle"]')).toBeInTheDocument();
  });
  it('状态有中文文字，不仅依赖颜色', () => {
    render(<StatusTag status="PAUSED" />);
    expect(screen.getByText('暂停中')).toBeVisible();
  });
  it('未知状态保守显示', () => {
    render(<StatusTag status="FUTURE_STATUS" />);
    expect(screen.getByText('未知状态：FUTURE_STATUS')).toBeVisible();
  });
  it('加载不能显示为空数据', () => {
    render(<LoadingState />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('暂无记录。')).not.toBeInTheDocument();
  });
  it('结果不明确时提示先查询，并保留请求 ID', () => {
    render(<ErrorNotice error={new ApiError(500, 'INTERNAL_ERROR', '请求结果尚未确认', 'request-test', true)} />);
    expect(screen.getByText('写入结果尚未确认，请先查询记录')).toBeVisible();
    expect(screen.getByText('请求 ID：request-test')).toBeVisible();
  });
  it('403 不误报会话已失效', () => {
    render(<ErrorNotice error={new ApiError(403, 'FORBIDDEN', '无权限')} />);
    expect(screen.getByText('当前凭据没有该操作权限，会话未被清除。')).toBeVisible();
  });
});
