import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  listActivityPage,
  parseStoredActivityEvent,
  recordActivity,
  recordAdminActivity,
  resetActivityThrottle
} from './activity';
import { kvGet, kvList, resetMemoryStore } from './storage';

describe('operational activity log', () => {
  beforeEach(() => {
    resetMemoryStore();
    resetActivityThrottle();
    delete process.env.ACTIVITY_RETENTION_DAYS;
    vi.useRealTimers();
  });

  it('uses reverse-time unique keys and applies bounded retention TTL', async () => {
    process.env.ACTIVITY_RETENTION_DAYS = '30';
    vi.setSystemTime(new Date('2026-08-12T00:00:00.000Z'));
    const first = await recordAdminActivity({
      action: 'device.created',
      administrator: 'admin',
      resource: { type: 'device', id: 'AB12C3' }
    });
    vi.setSystemTime(new Date('2026-08-12T00:00:01.000Z'));
    const second = await recordAdminActivity({
      action: 'device.updated',
      administrator: 'admin',
      resource: { type: 'device', id: 'AB12C3' }
    });
    const keys = await kvList('activity:v1:');
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(2);
    // Reverse timestamp ordering puts the newest event first.
    const events = await listActivityPage({}, { limit: 10 });
    expect(events.events.map((event) => event.id)).toEqual([second.id, first.id]);

    vi.setSystemTime(new Date(Date.now() + 30 * 24 * 60 * 60 * 1000 + 1));
    expect(await kvList('activity:v1:')).toEqual([]);
  });

  it('parses only allowlisted fields and drops sensitive/unknown values', () => {
    const parsed = parseStoredActivityEvent({
      schemaVersion: 1,
      id: 'event-1',
      occurredAt: '2026-08-12T00:00:00.000Z',
      category: 'administrator',
      action: 'device.updated',
      actor: { type: 'administrator', username: 'admin', ip: '192.0.2.1' },
      resource: { type: 'device', id: 'AB12C3', label: 'Radio', model: 't56', password: 'secret' },
      result: 'succeeded',
      configVersions: { previous: 1, current: 2, rawConfig: 'secret' },
      change: { sections: ['connections', 'channels', 'tracking', 'secret'], connectionsBefore: 1, channelsAfter: 2, metadata: 'secret' },
      correlationId: 'corr-1',
      metadata: { token: 'secret' }
    });
    expect(parsed).toEqual({
      schemaVersion: 1,
      id: 'event-1',
      occurredAt: '2026-08-12T00:00:00.000Z',
      category: 'administrator',
      action: 'device.updated',
      actor: { type: 'administrator', username: 'admin' },
      resource: { type: 'device', id: 'AB12C3', label: 'Radio', model: 't56' },
      result: 'succeeded',
      configVersions: { previous: 1, current: 2 },
      change: { sections: ['connections', 'channels', 'tracking'], connectionsBefore: 1, channelsAfter: 2 },
      correlationId: 'corr-1'
    });
  });

  it('paginates without duplicates and keeps filter fingerprints in the cursor', async () => {
    await recordActivity({
      category: 'device-configuration', action: 'config.request.succeeded', actor: { type: 'device' },
      result: 'served', occurredAt: '2026-08-12T00:00:03.000Z',
      resource: { type: 'device', id: 'AB12C3', model: 't56' }, configVersions: { served: 3 }
    });
    await recordActivity({
      category: 'device-configuration', action: 'config.request.unknown-device', actor: { type: 'device' },
      result: 'not-found', occurredAt: '2026-08-12T00:00:02.000Z',
      resource: { type: 'pending-device', id: 'CD34E5' }
    });
    await recordActivity({
      category: 'administrator', action: 'device.created', actor: { type: 'administrator', username: 'admin' },
      result: 'succeeded', occurredAt: '2026-08-12T00:00:01.000Z',
      resource: { type: 'device', id: 'AB12C3', model: 't56' }
    });

    const first = await listActivityPage({}, { limit: 2 });
    expect(first.events).toHaveLength(2);
    expect(first.nextCursor).toBeTruthy();
    const second = await listActivityPage({}, { limit: 2, cursor: first.nextCursor });
    expect(second.events).toHaveLength(1);
    expect(new Set([...first.events, ...second.events].map((event) => event.id)).size).toBe(3);

    const filtered = await listActivityPage({ category: 'device-configuration', deviceId: 'AB12C3' }, { limit: 10 });
    expect(filtered.events).toHaveLength(1);
    expect(filtered.events[0].configVersions?.served).toBe(3);
    await expect(listActivityPage({ category: 'administrator' }, { cursor: first.nextCursor })).rejects.toThrow('Invalid activity cursor');
  });

  it('continues selective scans past a full KV page and preserves the filter cursor', async () => {
    // Keep the first provider page entirely non-matching. The matching event
    // is older and must remain reachable through the opaque next cursor.
    await Promise.all(Array.from({ length: 1001 }, (_, index) => recordActivity({
      category: 'device-configuration',
      action: 'config.request.succeeded',
      actor: { type: 'device' },
      result: 'served',
      occurredAt: new Date(Date.UTC(2026, 7, 13, 0, 0, index)).toISOString(),
      resource: { type: 'device', id: 'CD34E5', model: 't56' }
    })));
    const matching = await recordActivity({
      category: 'device-configuration',
      action: 'config.request.succeeded',
      actor: { type: 'device' },
      result: 'served',
      occurredAt: '2026-08-12T00:00:00.000Z',
      resource: { type: 'device', id: 'AB12C3', model: 't56' }
    });

    const filters = { deviceId: 'AB12C3', action: 'config.request.succeeded' } as const;
    const first = await listActivityPage(filters, { limit: 1 });
    expect(first.events).toEqual([]);
    expect(first.nextCursor).toBeTruthy();

    const second = await listActivityPage(filters, { limit: 1, cursor: first.nextCursor });
    expect(second.events.map((event) => event.id)).toEqual([matching.id]);
    expect(second.nextCursor).toBeUndefined();
    await expect(listActivityPage({ ...filters, model: 'ryks' }, { cursor: first.nextCursor }))
      .rejects.toThrow('Invalid activity cursor');
  });

  it('records a successful administrator event with no raw request data', async () => {
    const event = await recordAdminActivity({
      action: 'admin.login.succeeded',
      administrator: 'admin',
      resource: { type: 'system' }
    });
    expect(event.actor).toEqual({ type: 'administrator', username: 'admin' });
    expect(event).not.toHaveProperty('password');
    expect(await kvGet(`activity:v1:${'0'.repeat(13)}:${event.id}`)).toBeNull();
  });
});
