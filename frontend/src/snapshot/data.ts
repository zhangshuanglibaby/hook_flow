export const snapshotStatuses = ['PENDING', 'SUCCESS', 'FINAL_FAILED', 'WAIT_RETRY', 'PAUSED'] as const;
export type SnapshotStatus = typeof snapshotStatuses[number];
export const snapshotLabels: Record<SnapshotStatus, string> = {
  PENDING: '待投递', SUCCESS: '成功', FINAL_FAILED: '最终失败', WAIT_RETRY: '等待重试', PAUSED: '暂停中',
};
export const snapshotEndpoints = [
  { id: 1, name: '积分服务' }, { id: 2, name: '库存服务' }, { id: 3, name: '内置故障模拟器' },
  { id: 4, name: '订单服务' }, { id: 5, name: '采购服务' },
] as const;
const eventTypes = ['order.paid', 'inventory.reserved', 'payment.confirmed', 'order.created', 'purchase.created', 'shipment.created'];
export interface SnapshotAttempt {
  attemptNo: number;
  result: 'SUCCESS' | 'HTTP_FAILURE';
  httpStatus: number;
  startedAt: string;
  endedAt: string;
}
export interface SnapshotEvent {
  eventId: string;
  eventType: string;
  endpointId: number;
  endpointName: string;
  status: SnapshotStatus;
  createdAt: string;
  nextRunAt: string | null;
  attempts: SnapshotAttempt[];
  payload: { fictional: true; orderId: string; amount: number; currency: string };
}

// 固定快照不随时钟推进，也不表示这些请求曾由后端执行。
export const snapshotEvents: SnapshotEvent[] = Array.from({ length: 30 }, (_, index) => {
  const status = snapshotStatuses[index % 5];
  const endpoint = snapshotEndpoints[(Math.floor(index / 5) + index % 5) % 5];
  const created = Date.parse('2026-11-02T04:30:00Z') - index * 3_600_000;
  const count = status === 'SUCCESS' ? 1 : status === 'FINAL_FAILED' ? 3 : status === 'WAIT_RETRY' ? 1 : 0;
  const attempts: SnapshotAttempt[] = Array.from({ length: count }, (_, attempt) => {
    const started = created + 1000 + [0, 10_100, 40_200][attempt];
    return { attemptNo: attempt + 1, result: status === 'SUCCESS' ? 'SUCCESS' : 'HTTP_FAILURE',
      httpStatus: status === 'SUCCESS' ? 200 : 500,
      startedAt: new Date(started).toISOString(), endedAt: new Date(started + 100).toISOString() };
  });
  return { eventId: `evt-snapshot-${String(1030 - index).padStart(4, '0')}`,
    eventType: eventTypes[index % eventTypes.length], endpointId: endpoint.id, endpointName: endpoint.name,
    status, createdAt: new Date(created).toISOString(),
    nextRunAt: status === 'WAIT_RETRY' ? new Date(created + 11_100).toISOString() : null,
    attempts, payload: { fictional: true as const, orderId: `DEMO-${String(index + 1).padStart(4, '0')}`, amount: 100 + index * 17, currency: 'CNY' } };
});

export interface SnapshotFilters { eventId?: string; eventType?: string; endpointId?: number; status?: SnapshotStatus; from?: string; to?: string }
export function filterSnapshot(events: readonly SnapshotEvent[], filters: SnapshotFilters) {
  return events.filter((event) => (!filters.eventId || event.eventId === filters.eventId)
    && (!filters.eventType || event.eventType === filters.eventType)
    && (!filters.endpointId || event.endpointId === filters.endpointId)
    && (!filters.status || event.status === filters.status)
    && (!filters.from || event.createdAt >= filters.from)
    && (!filters.to || event.createdAt < filters.to));
}
export function snapshotPage(events: readonly SnapshotEvent[], page: number, size: number) {
  return events.slice((page - 1) * size, page * size);
}
