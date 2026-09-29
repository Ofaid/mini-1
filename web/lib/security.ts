import { createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

const SESSION_COOKIE = 'minimum_admin_session';
const SESSION_TTL_SECONDS = 60 * 60 * 8;

function sessionSecret() {
  const value = process.env.SESSION_SECRET;
  if (value && value.length >= 32) return value;
  if (process.env.NODE_ENV !== 'production') return 'development-only-session-secret-012345678901234';
  throw new Error('SESSION_SECRET must be configured with at least 32 characters');
}

function base64Url(value: Buffer | string) {
  return Buffer.from(value).toString('base64url');
}

function fromBase64Url(value: string) {
  return Buffer.from(value, 'base64url').toString('utf8');
}

export function hashSecret(secret: string) {
  const salt = randomBytes(16).toString('hex');
  const derived = scryptSync(secret, salt, 32).toString('hex');
  return `scrypt$${salt}$${derived}`;
}

export function verifySecret(secret: string, encoded: string) {
  const [algorithm, salt, expectedHex] = encoded.split('$');
  if (algorithm !== 'scrypt' || !salt || !expectedHex || expectedHex.length !== 64) return false;
  try {
    const actual = scryptSync(secret, salt, 32);
    const expected = Buffer.from(expectedHex, 'hex');
    return expected.length === actual.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

export function createSession(username: string) {
  const payload = base64Url(JSON.stringify({
    username,
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS,
    nonce: randomBytes(12).toString('hex')
  }));
  const signature = createHmac('sha256', sessionSecret()).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}

export function readSession(value: string | undefined) {
  if (!value) return null;
  const [payload, signature] = value.split('.');
  if (!payload || !signature) return null;
  const expected = createHmac('sha256', sessionSecret()).update(payload).digest('base64url');
  const actualBuffer = Buffer.from(signature);
  const expectedBuffer = Buffer.from(expected);
  if (actualBuffer.length !== expectedBuffer.length || !timingSafeEqual(actualBuffer, expectedBuffer)) return null;
  try {
    const parsed = JSON.parse(fromBase64Url(payload)) as { username?: string; exp?: number };
    if (!parsed.username || !parsed.exp || parsed.exp < Math.floor(Date.now() / 1000)) return null;
    return { username: parsed.username };
  } catch {
    return null;
  }
}

export function sessionCookieOptions() {
  return {
    name: SESSION_COOKIE,
    httpOnly: true,
    sameSite: 'strict' as const,
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: SESSION_TTL_SECONDS
  };
}

export function sessionCookieName() {
  return SESSION_COOKIE;
}

export function validUsername(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{3,64}$/.test(value);
}

export function validPassword(value: unknown): value is string {
  return typeof value === 'string' && value.length >= 12 && value.length <= 256;
}

export function validDeviceId(value: unknown): value is string {
  return typeof value === 'string' && /^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6}$/.test(value);
}

export function securityHeaders() {
  return {
    'Cache-Control': 'no-store, max-age=0',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; connect-src 'self'; frame-ancestors 'none'"
  };
}

export function sameOrigin(request: Request) {
  const origin = request.headers.get('origin');
  const host = request.headers.get('host');
  if (!origin || !host) return false;
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}
