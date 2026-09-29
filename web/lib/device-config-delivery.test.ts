import { beforeEach, describe, expect, it, vi } from 'vitest';
import { listActivityPage, resetActivityThrottle } from './activity';
import * as activity from './activity';
import { emptyConfig } from './default-config';
import { deliverDeviceConfig } from './device-config-delivery';
import {
  dismissPendingDeviceRequest,
  kvGet,
  kvPut,
  putDevice,
  resetMemoryStore
} from './storage';
import * as storage from './storage';

describe('public device config delivery', () => {
  beforeEach(() => {
    resetMemoryStore();
    resetActivityThrottle();
  });

  it('uses one generic 404 for invalid, unknown and dismissed IDs', async () => {
    const invalid = await deliverDeviceConfig('invalid');
    const unknown = await deliverDeviceConfig('AB12C3');
    await dismissPendingDeviceRequest('CD34E5');
    const dismissed = await deliverDeviceConfig('CD34E5');

    expect(invalid.status).toBe(404);
    expect(unknown.status).toBe(404);
    expect(dismissed.status).toBe(404);
    const bodies = await Promise.all([invalid.text(), unknown.text(), dismissed.text()]);
    expect(bodies[0]).toBe(bodies[1]);
    expect(bodies[1]).toBe(bodies[2]);
  });

  it('bounds invalid-id activity when the advisory write hangs', async () => {
    const activityTelemetry = vi.spyOn(activity, 'recordThrottledConfigRequestActivity')
      .mockImplementation(() => new Promise(() => undefined));
    const startedAt = Date.now();
    const response = await deliverDeviceConfig('invalid');
    const elapsedMs = Date.now() - startedAt;

    expect(response.status).toBe(404);
    expect(elapsedMs).toBeLessThan(1000);
    expect(activityTelemetry).toHaveBeenCalledTimes(1);
    activityTelemetry.mockRestore();
  });

  it('bounds concurrent unknown-device pending and activity bookkeeping', async () => {
    const pending = vi.spyOn(storage, 'recordPendingDeviceRequest')
      .mockImplementation(() => new Promise(() => undefined));
    const activityTelemetry = vi.spyOn(activity, 'recordThrottledConfigRequestActivity')
      .mockImplementation(() => new Promise(() => undefined));
    const startedAt = Date.now();
    const response = await deliverDeviceConfig('AB12C3');
    const elapsedMs = Date.now() - startedAt;

    expect(response.status).toBe(404);
    expect(elapsedMs).toBeLessThan(1000);
    expect(pending).toHaveBeenCalledWith('AB12C3');
    expect(activityTelemetry).toHaveBeenCalledTimes(1);
    pending.mockRestore();
    activityTelemetry.mockRestore();
  });

  it('delivers a registered device even when a stale dismissal marker exists', async () => {
    const config = emptyConfig('AB12C3', 'ryks');
    await putDevice({
      deviceId: 'AB12C3',
      label: 'Radio',
      model: 'ryks',
      config,
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
    await kvPut('dismissed-pending-device:AB12C3', {
      deviceId: 'AB12C3',
      dismissedAt: '2026-08-12T00:00:00.000Z',
      expiresAt: '2099-08-12T00:00:00.000Z'
    });

    // Dismissal against an already registered device must not make delivery
    // look unknown, even if an old marker is restored by a KV race.
    const response = await deliverDeviceConfig('AB12C3');
    expect(response.status).toBe(200);
    expect(await kvGet('dismissed-pending-device:AB12C3')).toBeNull();
  });

  it('records served delivery counters and precise activity without implying readiness', async () => {
    const config = emptyConfig('AB12C3', 'ryks');
    await putDevice({
      deviceId: 'AB12C3', label: 'Radio', model: 'ryks', config,
      createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z'
    });
    const response = await deliverDeviceConfig('AB12C3');
    expect(response.status).toBe(200);
    const stats = await storage.getDeviceDeliveryStats('AB12C3');
    expect(stats).toMatchObject({
      deviceId: 'AB12C3', profileCreatedAt: '2026-08-12T00:00:00.000Z',
      requestCount: 1, servedCount: 1, lastConfigVersionServed: config.configVersion
    });
    const activity = await listActivityPage({ deviceId: 'AB12C3' }, { limit: 10 });
    expect(activity.events[0]).toMatchObject({
      action: 'config.request.succeeded', category: 'device-configuration', result: 'served'
    });
    expect(JSON.stringify(activity.events[0])).not.toMatch(/Ready|Online|Applied/);
  });

  it('bounds stale dismissal cleanup while still serving a registered device', async () => {
    const config = emptyConfig('AB12C3', 'ryks');
    await putDevice({
      deviceId: 'AB12C3', label: 'Radio', model: 'ryks', config,
      createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z'
    });
    const cleanup = vi.spyOn(storage, 'clearDismissedPendingDevice')
      .mockImplementation(() => new Promise(() => undefined));
    const startedAt = Date.now();
    const response = await deliverDeviceConfig('AB12C3');
    const elapsedMs = Date.now() - startedAt;

    expect(response.status).toBe(200);
    expect(elapsedMs).toBeLessThan(1000);
    expect(cleanup).toHaveBeenCalledWith('AB12C3');
    cleanup.mockRestore();
  });

  it('does not fail a successful delivery when telemetry storage is unavailable', async () => {
    const config = emptyConfig('AB12C3', 'ryks');
    await putDevice({
      deviceId: 'AB12C3', label: 'Radio', model: 'ryks', config,
      createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z'
    });
    const failure = vi.spyOn(storage, 'recordDeviceConfigRequest').mockRejectedValueOnce(new Error('telemetry down'));
    const response = await deliverDeviceConfig('AB12C3');
    failure.mockRestore();
    expect(response.status).toBe(200);
  });

  it('bounds slow telemetry and dispatches both writes without delaying config delivery indefinitely', async () => {
    const config = emptyConfig('AB12C3', 'ryks');
    await putDevice({
      deviceId: 'AB12C3', label: 'Radio', model: 'ryks', config,
      createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z'
    });
    const storageTelemetry = vi.spyOn(storage, 'recordDeviceConfigRequest')
      .mockImplementation(() => new Promise(() => undefined));
    const activityTelemetry = vi.spyOn(activity, 'recordConfigRequestActivity')
      .mockImplementation(() => new Promise(() => undefined));

    const startedAt = Date.now();
    const response = await deliverDeviceConfig('AB12C3');
    const elapsedMs = Date.now() - startedAt;

    expect(response.status).toBe(200);
    expect(elapsedMs).toBeLessThan(1000);
    expect(storageTelemetry).toHaveBeenCalledTimes(1);
    expect(activityTelemetry).toHaveBeenCalledTimes(1);
    storageTelemetry.mockRestore();
    activityTelemetry.mockRestore();
  });

  it('throttles invalid and unknown request events while retaining generic 404s', async () => {
    await deliverDeviceConfig('bad-id');
    await deliverDeviceConfig('bad-id');
    await deliverDeviceConfig('CD34E5');
    await deliverDeviceConfig('CD34E5');
    const invalid = await listActivityPage({ action: 'config.request.invalid-device-id' }, { limit: 10 });
    const unknown = await listActivityPage({ action: 'config.request.unknown-device' }, { limit: 10 });
    expect(invalid.events).toHaveLength(1);
    expect(invalid.events[0].resource).toEqual({ type: 'system' });
    expect(unknown.events).toHaveLength(1);
    expect(unknown.events[0].resource?.id).toBe('CD34E5');
  });
});
