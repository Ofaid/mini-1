import { describe, expect, it } from 'vitest';
import { buildOverview, classifyDeviceStatus, isWithinWindow, DAY_MS } from './overview';
import type { StoredDevice } from './types';

const now = '2026-08-12T12:00:00.000Z';
const device = (deviceId: string, version: number, model: StoredDevice['model'] = 't56'): StoredDevice => ({
  deviceId,
  label: deviceId,
  model,
  createdAt: '2026-08-01T00:00:00.000Z',
  updatedAt: '2026-08-01T00:00:00.000Z',
  config: { schemaVersion: 1, configVersion: version, deviceId }
});

describe('overview calculations', () => {
  it('uses inclusive time boundaries and rejects future timestamps', () => {
    expect(isWithinWindow('2026-08-11T12:00:00.000Z', now, DAY_MS)).toBe(true);
    expect(isWithinWindow('2026-08-11T11:59:59.999Z', now, DAY_MS)).toBe(false);
    expect(isWithinWindow('2026-08-12T12:00:00.001Z', now, DAY_MS)).toBe(false);
  });

  it('prioritizes never fetched, version drift, then stale delivery', () => {
    expect(classifyDeviceStatus(2, undefined, undefined, now)).toBe('never-fetched');
    expect(classifyDeviceStatus(2, 1, '2026-08-12T11:59:00.000Z', now)).toBe('behind');
    expect(classifyDeviceStatus(2, 2, '2026-08-01T11:59:00.000Z', now)).toBe('stale');
    expect(classifyDeviceStatus(2, 2, '2026-08-12T11:59:00.000Z', now)).toBe('current');
  });

  it('aggregates fetched windows, attention, pending repetition and model counts', () => {
    const result = buildOverview({
      now,
      devices: [device('AA11BB', 2), device('CC22DD', 1), device('EE33FF', 3, 'generic-radio')],
      deliveryStats: {
        AA11BB: { deviceId: 'AA11BB', profileCreatedAt: device('AA11BB', 2).createdAt, requestCount: 1, servedCount: 1, lastServedAt: '2026-08-12T11:00:00.000Z', lastConfigVersionServed: 2 },
        CC22DD: { deviceId: 'CC22DD', profileCreatedAt: device('CC22DD', 1).createdAt, requestCount: 2, servedCount: 2, lastServedAt: '2026-07-01T11:00:00.000Z', lastConfigVersionServed: 1 }
      },
      pending: [{ deviceId: 'FF44GG', firstSeenAt: '2026-08-12T10:00:00.000Z', lastSeenAt: '2026-08-12T11:30:00.000Z', requestCount: 2 }],
      configChanges24h: 4,
      storage: { ok: true, backend: 'memory', latencyMs: 1 }
    });
    expect(result.cards).toEqual({ registered: 3, fetched24h: 1, fetched7d: 1, neverFetched: 1, noRecentFetch: 1, pending: 1, configChanges24h: 4 });
    expect(result.attention.map((item) => item.reason)).toEqual(['never-fetched', 'stale', 'pending-repeated']);
    expect(result.modelDistribution).toEqual([{ model: 't56', count: 2 }, { model: 'generic-radio', count: 1 }]);
  });

  it('ignores delivery telemetry from an older profile creation timestamp', () => {
    const result = buildOverview({
      now,
      devices: [device('AA11BB', 2)],
      deliveryStats: { AA11BB: { deviceId: 'AA11BB', profileCreatedAt: '2026-07-01T00:00:00.000Z', requestCount: 8, servedCount: 8, lastServedAt: '2026-08-12T11:00:00.000Z', lastConfigVersionServed: 2 } },
      pending: [],
      storage: { ok: true, backend: 'memory', latencyMs: 1 }
    });
    expect(result.devices[0].status).toBe('never-fetched');
    expect(result.devices[0].servedCount).toBe(0);
    expect(result.devices[0].lastServedVersion).toBeUndefined();
  });
});
