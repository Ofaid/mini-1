import { beforeEach, describe, expect, it } from 'vitest';
import { POST } from './route';
import { createSession, sessionCookieName } from '../../../../../../lib/security';
import { getDevice, putAdmin, putDevice, resetMemoryStore } from '../../../../../../lib/storage';
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
function request(body: unknown, origin = 'http://localhost:3000') {
  return new Request('http://localhost:3000/api/devices/AB12C3/config-import/apply', { method: 'POST', headers: new Headers({ cookie: `${sessionCookieName()}=${adminSession}`, origin, host: 'localhost:3000', 'content-type': 'application/json' }), body: JSON.stringify(body) });
}

describe('config import apply route', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    resetMemoryStore();
    await putAdmin({ username: 'admin', passwordHash: 'unused', createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' });
    await putDevice({ deviceId: 'AB12C3', label: 'Target', model: 't56', config: targetConfig, createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' } as StoredDevice);
    await createConfigPreset(preset);
  });

  it('requires origin/BotID mutation checks and never persists the returned draft', async () => {
    expect((await POST(request({ presetId: 'ops-preset', decisions: { connectionDuplicate: 'add', channelDuplicate: 'add' } }, 'https://attacker.example'), context))!.status).toBe(403);
    const response = (await POST(request({ presetId: 'ops-preset', decisions: { connectionDuplicate: 'add', channelDuplicate: 'add' }, inclusion: {} }), context))!;
    expect(response.status).toBe(200);
    const encoded = await response.text();
    expect(encoded).not.toContain('secret-password');
    expect(encoded).not.toContain('secret-token');
    const stored = await getDevice('AB12C3');
    expect((stored?.config.channels as unknown[]).length).toBe(1);
  });

  it('maps an explicitly selected source default only when the import option is enabled', async () => {
    const response = (await POST(request({
      presetId: 'ops-preset',
      selection: { connectionId: 'source', channelIds: ['ops'], includeDefaultChannel: true },
      defaultChannelId: 'ops',
      decisions: { connectionDuplicate: 'add', channelDuplicate: 'add', importDefaultChannel: true },
      inclusion: {}
    }), context))!;
    expect(response.status).toBe(200);
    const body = await response.json() as { draft: typeof targetConfig };
    expect((body.draft.radio as { defaultChannel?: string }).defaultChannel).toBe('ops');
  });
});
