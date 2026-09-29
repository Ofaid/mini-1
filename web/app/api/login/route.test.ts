import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { POST } from './route';
import * as loginRateLimit from '../../../lib/login-rate-limit';
import * as storage from '../../../lib/storage';
import { hashSecret, sessionCookieName } from '../../../lib/security';
import { resetLoginRateLimitMemoryStore } from '../../../lib/login-rate-limit-storage';

const previousBotIdEnforce = process.env.BOTID_ENFORCE;

function request(username = 'admin', password = 'correct horse battery staple') {
  return new Request('http://localhost:3000/api/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      origin: 'http://localhost:3000',
      host: 'localhost:3000',
      'x-vercel-forwarded-for': '203.0.113.8'
    },
    body: JSON.stringify({ username, password })
  });
}

describe('POST /api/login', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    vi.restoreAllMocks();
    storage.resetMemoryStore();
    resetLoginRateLimitMemoryStore();
    await storage.putAdmin({
      username: 'admin',
      passwordHash: hashSecret('correct horse battery staple'),
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
  });

  afterAll(() => {
    vi.restoreAllMocks();
    if (previousBotIdEnforce === undefined) delete process.env.BOTID_ENFORCE;
    else process.env.BOTID_ENFORCE = previousBotIdEnforce;
  });

  it('consumes admission quota for every valid success before setting the signed session cookie', async () => {
    const clientAdmission = vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValue({ allowed: true });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit').mockResolvedValue({ allowed: true });
    const first = await POST(request());
    const second = await POST(request());
    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(await first.json()).toEqual({ ok: true, username: 'admin' });
    expect(first.headers.get('set-cookie')).toMatch(new RegExp(`^${sessionCookieName()}=`));
    expect(clientAdmission).toHaveBeenCalledTimes(2);
    expect(accountAdmission).toHaveBeenCalledTimes(2);
    expect(clientAdmission).toHaveBeenNthCalledWith(1, expect.any(Request));
    expect(clientAdmission).toHaveBeenNthCalledWith(2, expect.any(Request));
    expect(accountAdmission).toHaveBeenNthCalledWith(1, true);
    expect(accountAdmission).toHaveBeenNthCalledWith(2, true);
  });

  it('admits before returning the generic invalid-credentials response', async () => {
    const clientAdmission = vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({ allowed: true });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit').mockResolvedValueOnce({ allowed: true });
    const response = await POST(request('other-user', 'wrong password'));
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: 'Invalid username or password' });
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(clientAdmission).toHaveBeenCalledWith(expect.any(Request));
    expect(accountAdmission).toHaveBeenCalledWith(false);
  });

  it('returns 429 before KV/account work when the client admission is denied', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({
      allowed: false,
      retryAfterSeconds: 417
    });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit');
    const getAdmin = vi.spyOn(storage, 'getAdmin');
    const response = await POST(request());
    expect(response.status).toBe(429);
    expect(response.headers.get('retry-after')).toBe('417');
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Too many attempts; try again later' });
    expect(getAdmin).not.toHaveBeenCalled();
    expect(accountAdmission).not.toHaveBeenCalled();
  });

  it('returns 429 and Retry-After when the configured-account admission is denied', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({ allowed: true });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit').mockResolvedValueOnce({
      allowed: false,
      retryAfterSeconds: 318
    });
    const response = await POST(request());
    expect(response.status).toBe(429);
    expect(response.headers.get('retry-after')).toBe('318');
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Too many attempts; try again later' });
    expect(accountAdmission).toHaveBeenCalledWith(true);
  });

  it('returns the same 429 shape when the bounded decoy-account admission is denied', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({ allowed: true });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit').mockResolvedValueOnce({
      allowed: false,
      retryAfterSeconds: 318
    });
    const response = await POST(request('random-name', 'wrong password'));
    expect(response.status).toBe(429);
    expect(response.headers.get('retry-after')).toBe('318');
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Too many attempts; try again later' });
    expect(accountAdmission).toHaveBeenCalledWith(false);
  });

  it('fails closed with a generic 503 and bounded Retry-After when admission is unavailable', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockRejectedValueOnce(new Error('private backend detail'));
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit');
    const getAdmin = vi.spyOn(storage, 'getAdmin');
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const response = await POST(request());
    expect(response.status).toBe(503);
    expect(response.headers.get('retry-after')).toBe(String(loginRateLimit.LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS));
    expect(response.headers.get('set-cookie')).toBeNull();
    expect(await response.json()).toEqual({ error: 'Authentication service unavailable' });
    expect(log).toHaveBeenCalledWith('Login rate limiter unavailable');
    expect(JSON.stringify(log.mock.calls)).not.toMatch(/private backend detail|203\.0\.113\.8|private-token/);
    expect(getAdmin).not.toHaveBeenCalled();
    expect(accountAdmission).not.toHaveBeenCalled();
  });

  it('fails closed without account admission when administrator storage is unavailable', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({ allowed: true });
    const accountAdmission = vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit');
    vi.spyOn(storage, 'getAdmin').mockRejectedValueOnce(new Error('private KV detail'));
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const response = await POST(request());

    expect(response.status).toBe(503);
    expect(response.headers.get('retry-after')).toBe(String(loginRateLimit.LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS));
    expect(await response.json()).toEqual({ error: 'Authentication service unavailable' });
    expect(accountAdmission).not.toHaveBeenCalled();
    expect(log).toHaveBeenCalledWith('Login authentication storage unavailable');
    expect(JSON.stringify(log.mock.calls)).not.toMatch(/private KV detail|admin|private-token/);
  });

  it('fails closed with a generic log when the account stage is unavailable', async () => {
    vi.spyOn(loginRateLimit, 'checkLoginClientRateLimit').mockResolvedValueOnce({ allowed: true });
    vi.spyOn(loginRateLimit, 'checkLoginAccountRateLimit')
      .mockRejectedValueOnce(new Error('private account D1 detail'));
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const response = await POST(request());

    expect(response.status).toBe(503);
    expect(response.headers.get('retry-after')).toBe(String(loginRateLimit.LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS));
    expect(await response.json()).toEqual({ error: 'Authentication service unavailable' });
    expect(log).toHaveBeenCalledWith('Login rate limiter unavailable');
    expect(JSON.stringify(log.mock.calls)).not.toMatch(/private account D1 detail|203\.0\.113\.8|private-token/);
  });
});
