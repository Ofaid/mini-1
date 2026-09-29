import { jsonResponse, errorResponse, readJson } from '../../../lib/api';
import { recordAdminActivity } from '../../../lib/activity';
import { requireHumanMutation } from '../../../lib/botid';
import {
  checkLoginAccountRateLimit,
  checkLoginClientRateLimit,
  LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS
} from '../../../lib/login-rate-limit';
import { createSession, sameOrigin, sessionCookieOptions, verifySecret } from '../../../lib/security';
import { getAdmin } from '../../../lib/storage';

export const runtime = 'nodejs';

function authenticationUnavailable() {
  return errorResponse('Authentication service unavailable', 503, {
    'Retry-After': String(LOGIN_RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS)
  });
}

function tooManyAttempts(retryAfterSeconds: number) {
  const safeRetryAfter = Number.isSafeInteger(retryAfterSeconds) && retryAfterSeconds > 0
    ? retryAfterSeconds
    : 1;
  return errorResponse('Too many attempts; try again later', 429, {
    'Retry-After': String(safeRetryAfter)
  });
}

export async function POST(request: Request) {
  if (!sameOrigin(request) || !(await requireHumanMutation())) return errorResponse('Browser verification required', 403);
  try {
    const clientAdmission = await checkLoginClientRateLimit(request);
    if (!clientAdmission.allowed) return tooManyAttempts(clientAdmission.retryAfterSeconds);
  } catch {
    // Do not attach the error, request, username, IP address or D1 configuration.
    console.error('Login rate limiter unavailable');
    return authenticationUnavailable();
  }
  const body = await readJson(request);
  const username = typeof body?.username === 'string' ? body.username : '';
  const password = typeof body?.password === 'string' ? body.password : '';
  let admin;
  try {
    admin = await getAdmin();
  } catch {
    console.error('Login authentication storage unavailable');
    return authenticationUnavailable();
  }
  try {
    // Admission precedes password verification and is never reset on success, so
    // valid logins consume the same fixed-window quota as failed attempts. Unknown
    // usernames share one bounded decoy bucket instead of creating per-name rows or
    // locking the real administrator bucket. This lab treats the admin username as
    // an identifier, not a secret; CONFIG_BACKEND.md records that deliberate tradeoff.
    const accountAdmission = await checkLoginAccountRateLimit(admin?.username === username);
    if (!accountAdmission.allowed) return tooManyAttempts(accountAdmission.retryAfterSeconds);
  } catch {
    // Do not attach the error, request, username, IP address or D1 configuration.
    console.error('Login rate limiter unavailable');
    return authenticationUnavailable();
  }
  if (!admin || admin.username !== username || !verifySecret(password, admin.passwordHash)) {
    return errorResponse('Invalid username or password', 401);
  }
  const response = jsonResponse({ ok: true, username });
  response.cookies.set({ ...sessionCookieOptions(), value: createSession(username) });
  await recordAdminActivity({ action: 'admin.login.succeeded', administrator: username, resource: { type: 'system' } }).catch(() => undefined);
  return response;
}
