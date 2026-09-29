import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { POST } from './route';
import * as activity from '../../../lib/activity';
import * as botid from '../../../lib/botid';
import { createSession, sessionCookieName } from '../../../lib/security';
import { listActivityPage } from '../../../lib/activity';
import { putAdmin, resetMemoryStore } from '../../../lib/storage';

const previousBotIdEnforce = process.env.BOTID_ENFORCE;
const adminSession = createSession('admin');

function request(options: { session?: boolean; origin?: string; host?: string } = {}) {
  const headers = new Headers();
  if (options.session !== false) headers.set('cookie', `${sessionCookieName()}=${adminSession}`);
  if (options.origin !== undefined) headers.set('origin', options.origin);
  if (options.host !== undefined) headers.set('host', options.host);
  return new Request('http://localhost:3000/api/logout', { method: 'POST', headers });
}

async function post(options: { session?: boolean; origin?: string; host?: string } = {}) {
  return (await POST(request(options)))!;
}

describe('POST /api/logout', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    vi.restoreAllMocks();
    resetMemoryStore();
    await putAdmin({
      username: 'admin',
      passwordHash: 'unused',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
  });

  afterAll(() => {
    vi.restoreAllMocks();
    if (previousBotIdEnforce === undefined) delete process.env.BOTID_ENFORCE;
    else process.env.BOTID_ENFORCE = previousBotIdEnforce;
  });

  it('requires an authenticated session and does not clear an unauthenticated cookie', async () => {
    const response = await post({ session: false, origin: 'http://localhost:3000', host: 'localhost:3000' });
    expect(response.status).toBe(401);
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Authentication required' });
  });

  it.each([
    { origin: 'https://attacker.example', host: 'localhost:3000' },
    { origin: undefined, host: 'localhost:3000' },
    { origin: 'http://localhost:3000', host: undefined }
  ])('rejects a valid session with an invalid or missing same-origin header (%j)', async (headers) => {
    const response = await post(headers);
    expect(response.status).toBe(403);
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Invalid request origin' });
  });

  it('requires BotID verification before clearing the session cookie', async () => {
    vi.spyOn(botid, 'requireHumanMutation').mockResolvedValueOnce(false);
    const response = await post({ origin: 'http://localhost:3000', host: 'localhost:3000' });
    expect(response.status).toBe(403);
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Browser verification required' });
  });

  it('clears the cookie for a valid mutation and records a safe logout audit event', async () => {
    const response = await post({ origin: 'http://localhost:3000', host: 'localhost:3000' });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(response.headers.get('set-cookie')).toMatch(new RegExp(`^${sessionCookieName()}=;`));
    expect(response.headers.get('set-cookie')).toMatch(/Max-Age=0/i);

    const events = await listActivityPage({ action: 'admin.logout' }, { limit: 10 });
    expect(events.events).toHaveLength(1);
    expect(events.events[0]).toMatchObject({
      action: 'admin.logout',
      actor: { type: 'administrator', username: 'admin' },
      resource: { type: 'system' },
      result: 'succeeded'
    });
    expect(JSON.stringify(events.events[0])).not.toMatch(/password|secret|token/i);
  });

  it('still clears the cookie when audit storage rejects', async () => {
    vi.spyOn(activity, 'recordAdminActivity').mockRejectedValueOnce(new Error('audit unavailable'));
    const response = await post({ origin: 'http://localhost:3000', host: 'localhost:3000' });
    expect(response.status).toBe(200);
    expect(response.headers.get('set-cookie')).toMatch(new RegExp(`^${sessionCookieName()}=;`));
  });

  it('is repeatable for the same valid session while never accepting a missing session', async () => {
    const headers = { origin: 'http://localhost:3000', host: 'localhost:3000' };
    expect((await post(headers)).status).toBe(200);
    expect((await post(headers)).status).toBe(200);
    expect((await post({ session: false, ...headers })).status).toBe(401);
  });
});
