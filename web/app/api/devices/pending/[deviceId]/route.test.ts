import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { DELETE } from './route';
import { createSession, sessionCookieName } from '../../../../../lib/security';
import {
  getDismissedPendingDevice,
  kvGet,
  putAdmin,
  recordPendingDeviceRequest,
  resetMemoryStore
} from '../../../../../lib/storage';

const previousBotIdEnforce = process.env.BOTID_ENFORCE;
const adminSession = createSession('admin');

function context(deviceId: string) {
  return { params: Promise.resolve({ deviceId }) };
}

function request(options: { session?: boolean; origin?: string; host?: string } = {}) {
  const headers = new Headers();
  if (options.session !== false) {
    headers.set('cookie', `${sessionCookieName()}=${adminSession}`);
  }
  if (options.origin !== undefined) headers.set('origin', options.origin);
  if (options.host !== undefined) headers.set('host', options.host);
  return new Request('http://localhost:3000/api/devices/pending/AB12C3', {
    method: 'DELETE',
    headers
  });
}

describe('DELETE /api/devices/pending/[deviceId]', () => {
  beforeEach(async () => {
    process.env.BOTID_ENFORCE = 'false';
    resetMemoryStore();
    await putAdmin({
      username: 'admin',
      passwordHash: 'test-only',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z'
    });
  });

  afterAll(() => {
    if (previousBotIdEnforce === undefined) delete process.env.BOTID_ENFORCE;
    else process.env.BOTID_ENFORCE = previousBotIdEnforce;
  });

  it('returns 401 without an authenticated admin session', async () => {
    const response = await DELETE(request({ session: false, origin: 'http://localhost:3000', host: 'localhost:3000' }), context('AB12C3'));
    expect(response!.status).toBe(401);
  });

  it.each([
    { origin: 'https://attacker.example', host: 'localhost:3000' },
    { origin: undefined, host: 'localhost:3000' },
    { origin: 'http://localhost:3000', host: undefined }
  ])('returns 403 for a valid session with an invalid or missing origin (%j)', async (headers) => {
    const response = await DELETE(request(headers), context('AB12C3'));
    expect(response!.status).toBe(403);
  });

  it('returns 400 for an invalid device ID after auth and origin checks', async () => {
    const response = await DELETE(request({ origin: 'http://localhost:3000', host: 'localhost:3000' }), context('bad-id'));
    expect(response!.status).toBe(400);
  });

  it('returns an idempotent 200 and establishes dismissal', async () => {
    await recordPendingDeviceRequest('AB12C3');
    const headers = { origin: 'http://localhost:3000', host: 'localhost:3000' };
    const first = await DELETE(request(headers), context('AB12C3'));
    const second = await DELETE(request(headers), context('AB12C3'));

    expect(first!.status).toBe(200);
    expect(second!.status).toBe(200);
    expect(await getDismissedPendingDevice('AB12C3')).not.toBeNull();
    expect(await kvGet('pending-device:AB12C3')).toBeNull();
  });
});
