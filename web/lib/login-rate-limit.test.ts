import { describe, expect, it } from 'vitest';
import {
  checkLoginAccountRateLimit,
  checkLoginClientRateLimit,
  createLoginRateLimitBucketHash,
  LOGIN_RATE_LIMIT_ACCOUNT_ATTEMPTS,
  LOGIN_RATE_LIMIT_CLIENT_ATTEMPTS,
  LOGIN_RATE_LIMIT_WINDOW_SECONDS
} from './login-rate-limit';
import { LoginRateLimitUnavailableError, type LoginRateLimitRecord } from './login-rate-limit-storage';

const secret = 'test-login-rate-secret-012345678901234567890';

function request(headers: HeadersInit = {}) {
  return new Request('https://minimum.example/api/login', { headers });
}

function record(bucketHash: string, attempts = 1): LoginRateLimitRecord {
  return { bucketHash, attempts, resetAt: 1_900, observedAt: 1_000 };
}

describe('login rate limit policy', () => {
  it('creates stable, scope-separated HMAC keys that do not contain identifiers', () => {
    const client = createLoginRateLimitBucketHash('client', '203.0.113.8', secret);
    const repeated = createLoginRateLimitBucketHash('client', '203.0.113.8', secret);
    const account = createLoginRateLimitBucketHash('account', '203.0.113.8', secret);
    expect(client).toBe(repeated);
    expect(client).not.toBe(account);
    expect(client).toMatch(/^v1:[A-Za-z0-9_-]{43}$/);
    expect(client).not.toContain('203.0.113.8');
  });

  it('trusts only the Vercel forwarding header for the client dimension', async () => {
    const captured: string[] = [];
    await checkLoginClientRateLimit(request({
      'x-forwarded-for': '198.51.100.99',
      'x-vercel-forwarded-for': '203.0.113.8'
    }), {
      keySecret: secret,
      reserveBucket: async (bucketHash) => {
        captured.push(bucketHash);
        return record(bucketHash);
      }
    });
    expect(captured).toEqual([createLoginRateLimitBucketHash('client', '203.0.113.8', secret)]);
  });

  it('maps invalid or multi-value trusted headers to one safe unknown bucket', async () => {
    const captured: string[] = [];
    const reserveBucket = async (bucketHash: string) => {
      captured.push(bucketHash);
      return record(bucketHash);
    };
    await checkLoginClientRateLimit(request({
      'x-vercel-forwarded-for': '203.0.113.8, 198.51.100.3'
    }), { keySecret: secret, reserveBucket });
    await checkLoginClientRateLimit(request({
      'x-vercel-forwarded-for': 'not-an-ip'
    }), { keySecret: secret, reserveBucket });
    expect(captured[0]).toBe(captured[1]);
    expect(captured[0]).toBe(createLoginRateLimitBucketHash('client', 'unknown', secret));
  });

  it('allows ten client attempts and denies the eleventh for the fixed window', async () => {
    let attempts = 0;
    const reserveBucket = async (bucketHash: string) => record(bucketHash, ++attempts);
    for (let count = 1; count <= LOGIN_RATE_LIMIT_CLIENT_ATTEMPTS; count += 1) {
      await expect(checkLoginClientRateLimit(request({
        'x-vercel-forwarded-for': '203.0.113.8'
      }), { keySecret: secret, reserveBucket })).resolves.toEqual({ allowed: true });
    }
    await expect(checkLoginClientRateLimit(request({
      'x-vercel-forwarded-for': '203.0.113.8'
    }), { keySecret: secret, reserveBucket })).resolves.toEqual({
      allowed: false,
      retryAfterSeconds: LOGIN_RATE_LIMIT_WINDOW_SECONDS
    });
  });

  it('applies one bounded configured-account bucket independently', async () => {
    let attempts = 0;
    const reserved: string[] = [];
    const reserveBucket = async (bucketHash: string) => {
      reserved.push(bucketHash);
      return record(bucketHash, ++attempts);
    };
    for (let count = 1; count <= LOGIN_RATE_LIMIT_ACCOUNT_ATTEMPTS; count += 1) {
      await expect(checkLoginAccountRateLimit(true, {
        keySecret: secret,
        reserveBucket
      })).resolves.toEqual({ allowed: true });
    }
    await expect(checkLoginAccountRateLimit(true, {
      keySecret: secret,
      reserveBucket
    })).resolves.toEqual({
      allowed: false,
      retryAfterSeconds: LOGIN_RATE_LIMIT_WINDOW_SECONDS
    });
    expect(new Set(reserved).size).toBe(1);
    expect(reserved[0]).toMatch(/^v1:[A-Za-z0-9_-]{43}$/);
  });

  it('uses one stable decoy bucket for unconfigured usernames without touching the admin bucket', async () => {
    const reserved: string[] = [];
    const reserveBucket = async (bucketHash: string) => {
      reserved.push(bucketHash);
      return record(bucketHash);
    };
    await checkLoginAccountRateLimit(false, { keySecret: secret, reserveBucket });
    await checkLoginAccountRateLimit(false, { keySecret: secret, reserveBucket });
    await checkLoginAccountRateLimit(true, { keySecret: secret, reserveBucket });
    expect(reserved[0]).toBe(reserved[1]);
    expect(reserved[0]).not.toBe(reserved[2]);
    expect(new Set(reserved).size).toBe(2);
  });

  it('fails closed when the key secret is shorter than 32 bytes', async () => {
    await expect(checkLoginClientRateLimit(request(), {
      keySecret: 'too-short',
      reserveBucket: async (bucketHash) => record(bucketHash)
    })).rejects.toBeInstanceOf(LoginRateLimitUnavailableError);
  });
});
