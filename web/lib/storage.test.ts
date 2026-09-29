import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearDismissedPendingDevice,
  checkStorageHealth,
  deletePendingDeviceRequest,
  getDeviceDeliveryStats,
  dismissPendingDeviceRequest,
  getDevice,
  isPendingDeviceDismissed,
  kvGet,
  kvListPage,
  kvPut,
  listPendingDeviceRequests,
  putDevice,
  recordDeviceConfigRequest,
  recordPendingDeviceRequest,
  resetMemoryStore
} from './storage';

describe('stored device migration', () => {
  beforeEach(() => resetMemoryStore());

  it('removes retired token fields from legacy device records', async () => {
    await kvPut('device:A1B2C3', {
      deviceId: 'A1B2C3',
      label: 'Radio',
      model: 'ryks',
      config: { schemaVersion: 3, configVersion: 12, deviceId: 'A1B2C3' },
      tokenHash: 'retired-hash',
      tokenCreatedAt: '2026-08-12T00:00:00.000Z',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });

    const device = await getDevice('A1B2C3');
    const stored = await kvGet<Record<string, unknown>>('device:A1B2C3');

    expect(device?.config.configVersion).toBe(12);
    expect(stored).not.toHaveProperty('tokenHash');
    expect(stored).not.toHaveProperty('tokenCreatedAt');
  });
});

describe('KV paging and operational delivery telemetry', () => {
  beforeEach(() => resetMemoryStore());

  it('provides deterministic lexicographic memory pages with opaque cursors', async () => {
    await kvPut('activity:v1:c', { value: 3 });
    await kvPut('activity:v1:a', { value: 1 });
    await kvPut('activity:v1:b', { value: 2 });
    const first = await kvListPage('activity:v1:', { limit: 2 });
    const second = await kvListPage('activity:v1:', { limit: 2, cursor: first.cursor });
    expect(first.keys).toEqual(['activity:v1:a', 'activity:v1:b']);
    expect(second.keys).toEqual(['activity:v1:c']);
    expect(second.cursor).toBeUndefined();
  });

  it('resets delivery stats when a profile is recreated and keeps newer timestamps', async () => {
    await recordDeviceConfigRequest('AB12C3', {
      profileCreatedAt: '2026-08-12T00:00:00.000Z',
      requestedAt: '2026-08-12T00:00:10.000Z',
      served: true,
      configVersionServed: 3
    });
    await recordDeviceConfigRequest('AB12C3', {
      profileCreatedAt: '2026-08-12T00:00:00.000Z',
      requestedAt: '2026-08-12T00:00:05.000Z',
      served: true,
      configVersionServed: 2
    });
    const unchanged = await getDeviceDeliveryStats('AB12C3');
    expect(unchanged?.lastRequestAt).toBe('2026-08-12T00:00:10.000Z');
    expect(unchanged?.lastConfigVersionServed).toBe(3);
    await recordDeviceConfigRequest('AB12C3', {
      profileCreatedAt: '2026-08-13T00:00:00.000Z',
      requestedAt: '2026-08-13T00:00:01.000Z',
      served: true,
      configVersionServed: 1
    });
    const reset = await getDeviceDeliveryStats('AB12C3');
    expect(reset).toMatchObject({
      profileCreatedAt: '2026-08-13T00:00:00.000Z',
      requestCount: 1,
      servedCount: 1,
      lastConfigVersionServed: 1
    });
  });

  it('reports a read-only memory storage health sentinel', async () => {
    await expect(checkStorageHealth()).resolves.toMatchObject({ ok: true, backend: 'memory' });
  });
});

describe('pending-device dismissal', () => {
  beforeEach(() => resetMemoryStore());

  it('suppresses new pending records for 24 hours and then expires in memory', async () => {
    await recordPendingDeviceRequest('AB12C3');
    await dismissPendingDeviceRequest('AB12C3');
    const firstMarker = await kvGet<Record<string, string>>('dismissed-pending-device:AB12C3');
    await dismissPendingDeviceRequest('AB12C3');
    expect(await kvGet('dismissed-pending-device:AB12C3')).toEqual(firstMarker);

    expect(await isPendingDeviceDismissed('AB12C3')).toBe(true);
    await recordPendingDeviceRequest('AB12C3');
    expect(await kvGet('pending-device:AB12C3')).toBeNull();

    vi.setSystemTime(new Date(Date.now() + 24 * 60 * 60 * 1000 + 1));
    expect(await isPendingDeviceDismissed('AB12C3')).toBe(false);
    await recordPendingDeviceRequest('AB12C3');
    expect(await kvGet('pending-device:AB12C3')).not.toBeNull();
    vi.useRealTimers();
  });

  it('allows registration cleanup to clear suppression before a later request', async () => {
    await dismissPendingDeviceRequest('AB12C3');
    expect(await isPendingDeviceDismissed('AB12C3')).toBe(true);

    await clearDismissedPendingDevice('AB12C3');
    await recordPendingDeviceRequest('AB12C3');

    expect(await isPendingDeviceDismissed('AB12C3')).toBe(false);
    expect(await kvGet('pending-device:AB12C3')).not.toBeNull();
  });

  it('deletes stale pending state without suppressing a registered device', async () => {
    await putDevice({
      deviceId: 'AB12C3',
      label: 'Radio',
      model: 'ryks',
      config: { schemaVersion: 3, configVersion: 1, deviceId: 'AB12C3' },
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
    await kvPut('pending-device:AB12C3', {
      deviceId: 'AB12C3',
      firstSeenAt: '2026-08-12T00:00:00.000Z',
      lastSeenAt: '2026-08-12T00:00:00.000Z',
      requestCount: 1
    });

    await dismissPendingDeviceRequest('AB12C3');

    expect(await getDevice('AB12C3')).not.toBeNull();
    expect(await kvGet('pending-device:AB12C3')).toBeNull();
    expect(await kvGet('dismissed-pending-device:AB12C3')).toBeNull();
  });

  it('treats marker establishment as effective when pending deletion fails', async () => {
    await recordPendingDeviceRequest('AB12C3');

    await dismissPendingDeviceRequest('AB12C3', {
      deletePendingDeviceRequest: async () => {
        throw new Error('simulated KV delete failure');
      }
    });

    expect(await isPendingDeviceDismissed('AB12C3')).toBe(true);
    expect(await kvGet('pending-device:AB12C3')).not.toBeNull();
  });

  it('leaves the pending row when marker establishment fails', async () => {
    await recordPendingDeviceRequest('AB12C3');

    await expect(dismissPendingDeviceRequest('AB12C3', {
      writeDismissedPendingDevice: async () => {
        throw new Error('simulated KV marker failure');
      }
    })).rejects.toThrow('simulated KV marker failure');

    expect(await isPendingDeviceDismissed('AB12C3')).toBe(false);
    expect(await kvGet('pending-device:AB12C3')).not.toBeNull();
  });

  it('rechecks registration after writing the marker and heals the race', async () => {
    await recordPendingDeviceRequest('AB12C3');
    const order: string[] = [];
    let deviceChecks = 0;

    await dismissPendingDeviceRequest('AB12C3', {
      deviceRecordExists: async () => {
        order.push('device');
        deviceChecks += 1;
        return deviceChecks > 1;
      },
      writeDismissedPendingDevice: async (marker) => {
        order.push('marker');
        await kvPut(`dismissed-pending-device:${marker.deviceId}`, marker);
      },
      clearDismissedPendingDevice: async (deviceId) => {
        order.push('clear');
        await clearDismissedPendingDevice(deviceId);
      },
      deletePendingDeviceRequest: async (deviceId) => {
        order.push('delete');
        await deletePendingDeviceRequest(deviceId);
      }
    });

    expect(order).toEqual(['device', 'marker', 'device', 'clear', 'delete']);
    expect(await kvGet('dismissed-pending-device:AB12C3')).toBeNull();
    expect(await kvGet('pending-device:AB12C3')).toBeNull();
  });

  it('filters dismissal and registered-device race residue from the admin list', async () => {
    await recordPendingDeviceRequest('AB12C3');
    await dismissPendingDeviceRequest('AB12C3');
    await recordPendingDeviceRequest('CD34E5');
    await putDevice({
      deviceId: 'CD34E5',
      label: 'Radio',
      model: 'ryks',
      config: { schemaVersion: 3, configVersion: 1, deviceId: 'CD34E5' },
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
    await recordPendingDeviceRequest('EF67G8');

    await expect(listPendingDeviceRequests()).resolves.toEqual([
      expect.objectContaining({ deviceId: 'EF67G8' })
    ]);
  });
});
