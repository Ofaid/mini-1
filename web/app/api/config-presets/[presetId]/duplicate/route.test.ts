import { beforeEach, describe, expect, it } from 'vitest';
import { POST } from './route';
import { createSession, sessionCookieName } from '../../../../../lib/security';
import { putAdmin, resetMemoryStore } from '../../../../../lib/storage';
import { createConfigPreset, getConfigPreset } from '../../../../../lib/config-preset-storage';

const adminSession = createSession('admin');
const preset = { schemaVersion: 1, id: 'ops-preset', name: 'Operations', connection: { id: 'server', host: 'voice.example.com', port: 64738 }, channels: [{ id: 'main', label: 'Main', connectionId: 'server', path: '/MAIN', access: { mode: 'none' } }] };
function request(body: unknown) { return new Request('http://localhost:3000/api/config-presets/ops-preset/duplicate', { method: 'POST', headers: new Headers({ cookie: `${sessionCookieName()}=${adminSession}`, origin: 'http://localhost:3000', host: 'localhost:3000', 'content-type': 'application/json' }), body: JSON.stringify(body) }); }
describe('config preset duplicate route', () => {
  beforeEach(async () => { process.env.BOTID_ENFORCE = 'false'; resetMemoryStore(); await putAdmin({ username: 'admin', passwordHash: 'unused', createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' }); await createConfigPreset(preset); });
  it('copies under a new explicit ID without exposing secrets', async () => { const source = await getConfigPreset('ops-preset'); const response = (await POST(request({ newId: 'ops-copy', newName: 'Copy', expectedUpdatedAt: source!.updatedAt }), { params: Promise.resolve({ presetId: 'ops-preset' }) }))!; expect(response.status).toBe(201); expect((await response.json()).preset.id).toBe('ops-copy'); expect(await getConfigPreset('ops-copy')).not.toBeNull(); });
});
