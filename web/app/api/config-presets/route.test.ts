import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { GET, POST } from './route';
import { createSession, sessionCookieName } from '../../../lib/security';
import { putAdmin, resetMemoryStore } from '../../../lib/storage';

const previousBotIdEnforce = process.env.BOTID_ENFORCE;
const adminSession = createSession('admin');

function request(method: string, body?: unknown, options: { session?: boolean; origin?: string; host?: string; url?: string } = {}) {
  const headers = new Headers({ 'content-type': 'application/json' });
  if (options.session !== false) headers.set('cookie', `${sessionCookieName()}=${adminSession}`);
  if (options.origin !== undefined) headers.set('origin', options.origin);
  if (options.host !== undefined) headers.set('host', options.host);
  return new Request(options.url || 'http://localhost:3000/api/config-presets', {
    method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
}

const preset = {
  schemaVersion: 1, id: 'ops-preset', name: 'Operations',
  connection: { id: 'server', host: 'voice.example.com', port: 64738, username: 'operator', password: 'secret' },
  channels: [{ id: 'main', label: 'Main', connectionId: 'server', path: '/MAIN', access: { mode: 'public', tokens: ['alpha'] } }]
};

describe('config preset collection route', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    resetMemoryStore();
    await putAdmin({ username: 'admin', passwordHash: 'unused', createdAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z' });
  });

  afterAll(() => {
    if (previousBotIdEnforce === undefined) delete process.env.BOTID_ENFORCE;
    else process.env.BOTID_ENFORCE = previousBotIdEnforce;
  });

  it('requires authentication for reads and origin for writes', async () => {
    expect(((await GET(request('GET', undefined, { session: false })))!).status).toBe(401);
    expect(((await POST(request('POST', preset, { origin: 'https://attacker.example', host: 'localhost:3000' })))!).status).toBe(403);
  });

  it('creates and lists only safe preset representations', async () => {
    const created = (await POST(request('POST', preset, { origin: 'http://localhost:3000', host: 'localhost:3000' })))!;
    expect(created.status).toBe(201);
    const encoded = await created.text();
    expect(encoded).not.toContain('secret');
    expect(encoded).not.toContain('operator');
    expect(encoded).not.toContain('alpha');
    const listed = (await GET(request('GET')))!;
    expect(listed.status).toBe(200);
    expect((await listed.json()).presets).toHaveLength(1);
  });

  it('rejects duplicate IDs with a safe conflict', async () => {
    const options = { origin: 'http://localhost:3000', host: 'localhost:3000' };
    expect((await POST(request('POST', preset, options)))!.status).toBe(201);
    const duplicate = (await POST(request('POST', preset, options)))!;
    expect(duplicate.status).toBe(409);
  });
});
