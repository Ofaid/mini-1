import { createHmac } from 'node:crypto';
import { isIP } from 'node:net';
import {
  LoginRateLimitUnavailableError,
  reserveLoginRateLimitBucket,
  type LoginRateLimitRecord
} from './login-rate-limit-storage';

export const LOGIN_RATE_LIMIT_WINDOW_SECONDS = 15 * 60;
export const LOGIN_RATE_LIMIT_CLIENT_ATTEMPTS = 10;
export const LOGIN_RATE_LIMIT_ACCOUNT_ATTEMPTS = 30;
export const LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS = 30;

const DEVELOPMENT_KEY_SECRET = 'development-login-rate-key-01234567890123456789';
const CLIENT_SCOPE = 'client';
const ACCOUNT_SCOPE = 'account';
const ADMIN_ACCOUNT_IDENTIFIER = 'configured-admin-account';
const DECOY_ACCOUNT_IDENTIFIER = 'unconfigured-account';

interface BucketPolicy {
  bucketHash: string;
  limit: number;
}

export type LoginRateLimitDecision =
  | { allowed: true }
  | { allowed: false; retryAfterSeconds: number };

export interface LoginRateLimitDependencies {
  keySecret?: string;
  reserveBucket?: (
    bucketHash: string,
    windowSeconds: number
  ) => Promise<LoginRateLimitRecord>;
}

function keySecret(override?: string) {
  const value = override ?? process.env.LOGIN_RATE_LIMIT_KEY_SECRET
    ?? (process.env.NODE_ENV === 'production' ? undefined : DEVELOPMENT_KEY_SECRET);
  if (!value || Buffer.byteLength(value, 'utf8') < 32) throw new LoginRateLimitUnavailableError();
  return value;
}

function canonicalClientIp(request: Request) {
  const forwarded = request.headers.get('x-vercel-forwarded-for')?.trim();
  if (!forwarded || forwarded.includes(',')) return 'unknown';
  const version = isIP(forwarded);
  if (version === 4) return forwarded;
  if (version === 6) {
    try {
      const hostname = new URL(`http://[${forwarded}]/`).hostname;
      return hostname.slice(1, -1).toLowerCase();
    } catch {
      return 'unknown';
    }
  }
  return 'unknown';
}

export function createLoginRateLimitBucketHash(scope: 'client' | 'account', identifier: string, secret: string) {
  return `v1:${createHmac('sha256', secret)
    .update(`minimum-login-rate:v1\0${scope}\0${identifier}`)
    .digest('base64url')}`;
}

async function checkBucket(
  policy: BucketPolicy,
  reserveBucket: NonNullable<LoginRateLimitDependencies['reserveBucket']>
): Promise<LoginRateLimitDecision> {
  const record = await reserveBucket(policy.bucketHash, LOGIN_RATE_LIMIT_WINDOW_SECONDS);
  if (
    record.bucketHash !== policy.bucketHash
    || !Number.isSafeInteger(record.attempts)
    || record.attempts < 1
    || !Number.isSafeInteger(record.resetAt)
    || !Number.isSafeInteger(record.observedAt)
    || record.resetAt <= record.observedAt
  ) {
    throw new LoginRateLimitUnavailableError();
  }
  if (record.attempts <= policy.limit) return { allowed: true };
  return { allowed: false, retryAfterSeconds: Math.max(1, record.resetAt - record.observedAt) };
}

export async function checkLoginClientRateLimit(
  request: Request,
  dependencies: LoginRateLimitDependencies = {}
): Promise<LoginRateLimitDecision> {
  const secret = keySecret(dependencies.keySecret);
  const reserveBucket = dependencies.reserveBucket ?? reserveLoginRateLimitBucket;
  return checkBucket({
    bucketHash: createLoginRateLimitBucketHash(CLIENT_SCOPE, canonicalClientIp(request), secret),
    limit: LOGIN_RATE_LIMIT_CLIENT_ATTEMPTS
  }, reserveBucket);
}

export async function checkLoginAccountRateLimit(
  targetsConfiguredAccount: boolean,
  dependencies: LoginRateLimitDependencies = {}
): Promise<LoginRateLimitDecision> {
  const secret = keySecret(dependencies.keySecret);
  const reserveBucket = dependencies.reserveBucket ?? reserveLoginRateLimitBucket;
  return checkBucket({
    bucketHash: createLoginRateLimitBucketHash(
      ACCOUNT_SCOPE,
      targetsConfiguredAccount ? ADMIN_ACCOUNT_IDENTIFIER : DECOY_ACCOUNT_IDENTIFIER,
      secret
    ),
    limit: LOGIN_RATE_LIMIT_ACCOUNT_ATTEMPTS
  }, reserveBucket);
}
