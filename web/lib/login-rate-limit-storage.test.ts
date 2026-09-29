import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  LoginRateLimitUnavailableError,
  reserveLoginRateLimitBucket,
  resetLoginRateLimitMemoryStore
} from './login-rate-limit-storage';

const clientHash = `v1:${'a'.repeat(43)}`;

describe('login rate limit storage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    resetLoginRateLimitMemoryStore();
  });

  afterEach(() => vi.unstubAllEnvs());

  it('provides deterministic fixed-window records in non-production memory', async () => {
    await expect(reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'memory',
      nowSeconds: 1_000
    })).resolves.toEqual({
      bucketHash: clientHash,
      attempts: 1,
      resetAt: 1_900,
      observedAt: 1_000
    });
    await expect(reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'memory',
      nowSeconds: 1_899
    })).resolves.toEqual({
      bucketHash: clientHash,
      attempts: 2,
      resetAt: 1_900,
      observedAt: 1_899
    });
    await expect(reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'memory',
      nowSeconds: 1_900
    })).resolves.toEqual({
      bucketHash: clientHash,
      attempts: 1,
      resetAt: 2_800,
      observedAt: 1_900
    });
  });

  it('uses one parameterized D1 statement and accepts a complete response', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (_input, _init) => new Response(JSON.stringify({
      success: true,
      result: [{
        success: true,
        results: [{ bucket_hash: clientHash, attempts: 11, reset_at: 1_900, observed_at: 1_100 }]
      }]
    }), { status: 200 }));

    await expect(reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'd1',
      d1Config: { accountId: 'account-id', databaseId: 'database-id', apiToken: 'private-token' },
      fetchImpl
    })).resolves.toEqual({ bucketHash: clientHash, attempts: 11, resetAt: 1_900, observedAt: 1_100 });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('https://api.cloudflare.com/client/v4/accounts/account-id/d1/database/database-id/query');
    expect(init?.method).toBe('POST');
    expect(init?.headers).toMatchObject({
      Authorization: 'Bearer private-token',
      'Content-Type': 'application/json'
    });
    const requestBody = JSON.parse(String(init?.body)) as { sql: string; params: string[] };
    expect(requestBody.params).toEqual([clientHash]);
    expect(requestBody.sql).toContain('INSERT INTO login_rate_limit_v1');
    expect(requestBody.sql).toContain('ON CONFLICT(bucket_hash) DO UPDATE');
    expect(requestBody.sql).toContain('RETURNING bucket_hash, attempts, reset_at, unixepoch() AS observed_at');
    expect(requestBody.sql.match(/INSERT INTO/g)).toHaveLength(1);
  });

  it.each([
    ['non-success HTTP response', async () => new Response('', { status: 503 })],
    ['malformed response', async () => new Response(JSON.stringify({ success: true, result: [] }), { status: 200 })],
    ['network rejection', async () => { throw new Error('network details'); }]
  ])('fails closed on a %s without exposing backend details', async (_label, implementation) => {
    const operation = reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'd1',
      d1Config: { accountId: 'account-id', databaseId: 'database-id', apiToken: 'private-token' },
      fetchImpl: vi.fn(implementation)
    });
    await expect(operation).rejects.toBeInstanceOf(LoginRateLimitUnavailableError);
    await expect(operation).rejects.not.toThrow(/network details|private-token/);
  });

  it('fails closed when the D1 resource configuration is incomplete', async () => {
    await expect(reserveLoginRateLimitBucket(clientHash, 900, {
      backend: 'd1',
      d1Config: { accountId: '', databaseId: '', apiToken: '' }
    })).rejects.toBeInstanceOf(LoginRateLimitUnavailableError);
  });

  it('selects D1 and fails closed by default when production is misconfigured', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('CLOUDFLARE_ACCOUNT_ID', '');
    vi.stubEnv('CLOUDFLARE_D1_DATABASE_ID', '');
    vi.stubEnv('CLOUDFLARE_D1_API_TOKEN', '');
    await expect(reserveLoginRateLimitBucket(clientHash, 900))
      .rejects.toBeInstanceOf(LoginRateLimitUnavailableError);
  });

  it('prunes buckets that expired more than 24 hours ago after every insert or update', () => {
    const migration = readFileSync(
      new URL('../cloudflare/d1/0001_login_rate_limit.sql', import.meta.url),
      'utf8'
    );
    expect(migration).toContain('CREATE INDEX IF NOT EXISTS login_rate_limit_v1_reset_at');
    expect(migration).toMatch(/AFTER INSERT ON login_rate_limit_v1[\s\S]*reset_at < unixepoch\(\) - 86400/);
    expect(migration).toMatch(/AFTER UPDATE ON login_rate_limit_v1[\s\S]*reset_at < unixepoch\(\) - 86400/);
  });
});
