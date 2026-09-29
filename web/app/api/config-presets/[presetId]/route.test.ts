import { beforeEach, describe, expect, it } from 'vitest';
import { DELETE, GET, PATCH } from './route';
import { createSession, sessionCookieName } from '../../../../lib/security';
import { putAdmin, resetMemoryStore } from '../../../../lib/storage';
import { createConfigPreset, getConfigPreset } from '../../../../lib/config-preset-storage';

const adminSession = createSession('admin');
const basePreset = {
  schemaVersion: 1, id: 'ops-preset', name: 'Operations', connection: { id: 'server', host: 'voice.example.com', port: 64738 },
  channels: [{ id: 'main', label: 'Main', connectionId: 'server', path: '/MAIN', access: { mode: 'none' } }]
};
function request(method: string, body?: unknown) {
  return new Request('http://localhost:3000/api/config-presets/ops-preset', {
    method,
    headers: new Headers({ cookie: `${sessionCookieName()}=${adminSession}`, origin: 'http://localhost:3000', host: 'localhost:3000', 'content-type': 'application/json' }),
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
}
const context = { params: Promise.resolve({ presetId: 'ops-preset' }) };

describe('config preset item route', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    resetMemoryStore();
    await putAdmin({ username: 'admin', passwordHash: 'unused', createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' });
    await createConfigPreset(basePreset, '2026-08-12T00:00:00.000Z');
  });

  it('supports safe GET and optimistic PATCH/DELETE', async () => {
    const read = (await GET(request('GET'), context))!;
    expect(read.status).toBe(200);
    const current = await getConfigPreset('ops-preset');
    const updated = (await PATCH(request('PATCH', { expectedUpdatedAt: current?.updatedAt, preset: { ...basePreset, name: 'Updated' } }), context))!;
    expect(updated.status).toBe(200);
    const next = await getConfigPreset('ops-preset');
    expect((await DELETE(request('DELETE', { expectedUpdatedAt: next!.updatedAt }), context))!.status).toBe(200);
    expect(await getConfigPreset('ops-preset')).toBeNull();
  });

  it('returns 409 on stale optimistic writes', async () => {
    const response = (await PATCH(request('PATCH', { expectedUpdatedAt: 'stale', preset: basePreset }), context))!;
    expect(response.status).toBe(409);
  });

  it('requires optimistic concurrency tokens for writes', async () => {
    expect((await PATCH(request('PATCH', { preset: basePreset }), context))!.status).toBe(428);
    expect((await DELETE(request('DELETE'), context))!.status).toBe(428);
  });
});
