import { LoadingState, PageHeading } from '../shared/ui';

export function StatePreviewPage() {
  return <><PageHeading title="组件开发预览" description="此组件不注册业务路由，不提供静态业务结果。" /><LoadingState /></>;
}
