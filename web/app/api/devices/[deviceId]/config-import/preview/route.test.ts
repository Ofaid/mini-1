import { beforeEach, describe, expect, it } from 'vitest';
import { POST } from './route';
import { createSession, sessionCookieName } from '../../../../../../lib/security';
import { putAdmin, putDevice, resetMemoryStore } from '../../../../../../lib/storage';
import { createConfigPreset } from '../../../../../../lib/config-preset-storage';
import type { StoredDevice } from '../../../../../../lib/types';

const adminSession = createSession('admin');
const context = { params: Promise.resolve({ deviceId: 'AB12C3' }) };
const targetConfig = {
  schemaVersion: 3, configVersion: 9, deviceId: 'AB12C3', modelProfile: 't56', service: { name: 'target' },
  hardware: { profile: 'target-hardware', pttKeyCode: 1 }, ptt: { maximumTxSeconds: 120, releaseOnNetworkLoss: true },
  connections: { target: { host: 'target.example.com', port: 64738, username: 'target-user', autoTrustServerCertificate: true } },
  channels: [{ id: 'target', label: 'Target', connectionId: 'target', path: '/TARGET', presetKey: 'P1', access: { mode: 'none' } }],
  radio: { defaultChannel: 'target', autoConnect: false }, ui: { profile: 'small-radio' }
};
const preset = {
  schemaVersion: 1, id: 'ops-preset', name: 'Operations',
  connection: { id: 'source', host: 'source.example.com', port: 64738, username: 'secret-user', password: 'secret-password' },
  channels: [{ id: 'ops', label: 'Operations', connectionId: 'source', path: '/OPS', access: { mode: 'public', tokens: ['secret-token'] } }]
};

function request(body: unknown, method = 'POST', origin = 'http://localhost:3000') {
  return new Request('http://localhost:3000/api/devices/AB12C3/config-import/preview', {
    method,
    headers: new Headers({ cookie: `${sessionCookieName()}=${adminSession}`, origin, host: 'localhost:3000', 'content-type': 'application/json' }),
    body: JSON.stringify(body)
  });
}

describe('config import preview route', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    resetMemoryStore();
    await putAdmin({ username: 'admin', passwordHash: 'unused', createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' });
    await putDevice({ deviceId: 'AB12C3', label: 'Target', model: 't56', config: targetConfig, createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' } as StoredDevice);
    await createConfigPreset(preset);
  });

  it('requires an authenticated admin and returns a masked preview', async () => {
    const unauthenticated = new Request('http://localhost:3000/api/devices/AB12C3/config-import/preview', { method: 'POST', body: '{}' });
    expect((await POST(unauthenticated, context))!.status).toBe(401);
    const response = (await POST(request({ presetId: 'ops-preset', targetDraft: targetConfig, inclusion: {}, decisions: { connectionDuplicate: 'add', channelDuplicate: 'add' } }), context))!;
    expect(response.status).toBe(200);
    const encoded = await response.text();
    expect(encoded).not.toContain('secret-password');
    expect(encoded).not.toContain('secret-token');
  });
});
