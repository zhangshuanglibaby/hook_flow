// 开发状态画廊只在 Vite 开发模式注册，不加入正常业务导航。
import { useState } from 'react';
import { Radio } from 'antd';
import type { RadioChangeEvent } from 'antd';
import { DisplayState, PageHeading, StaticNotice } from '../shared/ui';

// 预览四种互不混淆的展示状态，不拦截网络、不伪造接口响应。
export function StatePreviewPage() {
  // 当前预览类型只属于开发画廊，不影响任何业务页面。
  const [kind, setKind] = useState<'loading' | 'empty' | 'no-match' | 'error'>('loading');
  // 将开发单选按钮的值作为当前样式场景。
  function changeKind(event: RadioChangeEvent) {
    // 值来自下方固定选项，不更改静态数据源。
    setKind(event.target.value as typeof kind);
  }
  // 不使用加载延迟或失败回退来假装请求已经运行。
  return <><PageHeading title="开发样式预览" description="独立开发入口，不属于业务功能，也不在生产构建开放。" /><StaticNotice>这里直接给展示组件传入状态，不发送请求、不模拟接口、不自动切换成成功。</StaticNotice><Radio.Group className="state-switcher" value={kind} onChange={changeKind} options={[{ value: 'loading', label: '加载中' }, { value: 'empty', label: '空数据' }, { value: 'no-match', label: '无匹配结果' }, { value: 'error', label: '错误展示' }]} optionType="button" /><DisplayState kind={kind} /></>;
}
