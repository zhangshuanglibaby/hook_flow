import { describe, expect, it } from 'vitest';
import { filterSnapshot, snapshotEndpoints, snapshotEvents, snapshotPage, snapshotStatuses } from '../../src/snapshot/data';

describe('独立展示快照', () => {
  it('恰好30条唯一记录，五状态各6条', () => {
    expect(snapshotEvents).toHaveLength(30);
    expect(new Set(snapshotEvents.map((e) => e.eventId)).size).toBe(30);
    for (const status of snapshotStatuses) expect(snapshotEvents.filter((e) => e.status === status)).toHaveLength(6);
  });
  it('五个具名接收端各6条，均为虚构数据', () => {
    expect(snapshotEndpoints.map((e) => e.name)).toEqual(['积分服务', '库存服务', '内置故障模拟器', '订单服务', '采购服务']);
    for (const endpoint of snapshotEndpoints) {
      const events = snapshotEvents.filter((e) => e.endpointId === endpoint.id);
      expect(events).toHaveLength(6);
      expect(events.every((e) => e.endpointName === endpoint.name && e.payload.fictional)).toBe(true);
    }
  });
  it('分页20加10，无重复或丢失', () => {
    const first = snapshotPage(snapshotEvents, 1, 20);
    const second = snapshotPage(snapshotEvents, 2, 20);
    expect(first).toHaveLength(20); expect(second).toHaveLength(10);
    expect([...first, ...second]).toEqual(snapshotEvents);
  });
  it('筛选使用精确身份与半开时间边界，不修改原数据', () => {
    const first = snapshotEvents[0];
    expect(filterSnapshot(snapshotEvents, { eventId: first.eventId })).toEqual([first]);
    expect(filterSnapshot(snapshotEvents, { eventId: 'evt' })).toEqual([]);
    expect(filterSnapshot(snapshotEvents, { from: first.createdAt })).toEqual([first]);
    expect(filterSnapshot(snapshotEvents, { to: first.createdAt })).toHaveLength(29);
    expect(filterSnapshot(snapshotEvents, { status: 'PAUSED' })).toHaveLength(6);
    expect(snapshotEvents).toHaveLength(30);
  });
  it('状态与演示历史一致，未发送的记录不虚构HTTP结果', () => {
    for (const event of snapshotEvents) {
      const count = event.status === 'SUCCESS' ? 1 : event.status === 'FINAL_FAILED' ? 3 : event.status === 'WAIT_RETRY' ? 1 : 0;
      expect(event.attempts).toHaveLength(count);
      expect(event.nextRunAt !== null).toBe(event.status === 'WAIT_RETRY');
      expect(event.attempts.every((a) => a.httpStatus === (event.status === 'SUCCESS' ? 200 : 500))).toBe(true);
    }
  });
});
