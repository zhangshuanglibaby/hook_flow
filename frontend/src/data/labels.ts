// 只引入类型，避免展示模型产生运行时代码。
import type { DeliveryStatus, AttemptResult, SimulatorMode } from './types';

// 集中定义六种任务中文名称，列表、详情和概览使用相同文案。
export const statusLabels: Record<DeliveryStatus, string> = {
  PENDING: '待投递', DELIVERING: '投递中', WAIT_RETRY: '等待重试',
  PAUSED: '暂停中', SUCCESS: '成功', FINAL_FAILED: '最终失败',
};
// 尝试结果使用独立名称，成功明确表达为“确认接收”。
export const attemptLabels: Record<AttemptResult, string> = {
  STARTED: '请求进行中', SUCCESS: '确认接收', HTTP_FAILURE: 'HTTP 失败',
  UNKNOWN: '结果未知', LOCAL_FAILURE: '本地失败',
};
// 模拟器单选按钮与只读接收表共用这组名称。
export const modeLabels: Record<SimulatorMode, string> = {
  ALWAYS_200: '始终返回 200', ALWAYS_500: '始终返回 500',
  FAIL_FIRST_N: '前 N 次失败后成功', PROCESSED_THEN_DELAY: '已处理后延迟响应',
};
// 每个模式旁边给出静态规则说明，不把规则说明当作真实结果。
export const modeDescriptions: Record<SimulatorMode, string> = {
  ALWAYS_200: '每次确认接收，展示首次成功的投递路径。',
  ALWAYS_500: '持续返回服务器错误，展示自动重试与次数耗尽。',
  FAIL_FIRST_N: '前 N 次返回 500；之后返回 200。500 不记为业务已处理。',
  PROCESSED_THEN_DELAY: '先完成处理，再延迟响应；超时结果未知，后续重试幂等确认。',
};
// 审计仅映射已知操作；未知值仍按安全文本显示。
export const auditLabels: Record<string, string> = {
  ENDPOINT_CREATE: '创建接收端', ENDPOINT_UPDATE: '编辑接收端',
  ENDPOINT_ENABLE: '启用接收端', ENDPOINT_DISABLE: '停用接收端',
  SIMULATOR_CONFIG_UPDATE: '保存模拟配置', EVENT_REDELIVER: '手动重投',
};
