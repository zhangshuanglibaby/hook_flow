// 六种任务状态仅描述一轮投递；UNKNOWN 属于尝试结果，不混入此类型。
export type DeliveryStatus = 'PENDING' | 'DELIVERING' | 'WAIT_RETRY' | 'PAUSED' | 'SUCCESS' | 'FINAL_FAILED';
// 尝试结果与任务状态分开，防止把超时误显示为确定失败。
export type AttemptResult = 'STARTED' | 'SUCCESS' | 'HTTP_FAILURE' | 'UNKNOWN' | 'LOCAL_FAILURE';
// 四种模式只决定静态表单中显示的参数，不执行投递。
export type SimulatorMode = 'ALWAYS_200' | 'ALWAYS_500' | 'FAIL_FIRST_N' | 'PROCESSED_THEN_DELAY';
// 本地接收端展示模型不是已冻结的后端 DTO。
export interface EndpointSample {
  readonly id: number;
  readonly name: string;
  readonly targetType: 'EXTERNAL' | 'SIMULATOR';
  readonly url: string | null;
  readonly timeoutMs: number;
  readonly enabled: boolean;
}
// 可空字段保留 null，使 0 ms 与尚未结束在界面中可以区分。
export interface AttemptSample {
  readonly attemptNo: number;
  readonly result: AttemptResult;
  readonly startedAt: string;
  readonly endedAt: string | null;
  readonly durationMs: number | null;
  readonly httpStatus: number | null;
  readonly responseExcerpt: string | null;
  readonly errorMessage: string | null;
}
// 快照显式属于当前静态轮次；不能用接收端当前配置替代历史快照。
export interface RoundSample {
  readonly roundNo: number;
  readonly status: DeliveryStatus;
  readonly maxAttempts: number;
  readonly snapshotUrl: string | null;
  readonly snapshotTimeoutMs: number;
  readonly attempts: readonly AttemptSample[];
}
// payload 保留原始展示文本，本阶段不提交或格式化用户的高精度数字。
export interface EventSample {
  readonly eventId: string;
  readonly eventType: string;
  readonly endpointId: number;
  readonly status: DeliveryStatus;
  readonly latestRoundNo: number;
  readonly attemptCount: number;
  readonly maxAttempts: number;
  readonly createdAt: string;
  readonly lastAttemptAt: string | null;
  readonly payloadText: string;
  readonly rounds: readonly RoundSample[];
}
// 接收记录保持独立输入，不根据模式或事件列表合成结果。
export interface ReceiptSample {
  readonly id: number;
  readonly eventId: string;
  readonly receivedAt: string;
  readonly configMode: SimulatorMode;
  readonly plannedStatus: number;
  readonly signatureValid: boolean;
  readonly duplicateProcessed: boolean;
}
// 审计使用内部 ID 展示，不把 objectId 冒充外部事件 ID。
export interface AuditSample {
  readonly id: number;
  readonly actorUserId: number;
  readonly action: string;
  readonly objectType: string;
  readonly objectId: number;
  readonly occurredAt: string;
  readonly requestId: string;
}
