import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Button, Result } from 'antd';

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  // failed=true 时显示局部兜底页，避免一个组件崩溃导致整个页面白屏。
  state = { failed: false };
  // React 捕获渲染错误后，切换到兜底状态。
  static getDerivedStateFromError() { return { failed: true }; }
  // 当前不向外部错误平台发送信息，避免把请求内容或凭据带出浏览器。
  componentDidCatch(_error: Error, _info: ErrorInfo) { void _error; void _info; }
  render() {
    return this.state.failed
      ? <Result status="error" title="页面暂时无法显示" subTitle="请重新打开工作台。当前输入可能未保存。" extra={<Button onClick={() => window.location.assign('/events')}>重新打开工作台</Button>} />
      : this.props.children;
  }
}
