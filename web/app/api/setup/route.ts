import { jsonResponse, errorResponse, readJson } from '@/lib/api';
import { requireHumanMutation } from '@/lib/botid';
import { hashSecret, validPassword, validUsername, sameOrigin, createSession, sessionCookieOptions } from '@/lib/security';
import { getAdmin, putAdmin } from '@/lib/storage';

export const runtime = 'nodejs';

export async function POST(request: Request) {
  if (!sameOrigin(request) || !(await requireHumanMutation())) return errorResponse('Browser verification required', 403);
  if (await getAdmin()) return errorResponse('Admin account already configured', 409);
  const body = await readJson(request);
  const username = body?.username;
  const password = body?.password;
  const confirmation = body?.confirmation;
  if (!validUsername(username) || !validPassword(password) || password !== confirmation) {
    return errorResponse('Use a valid username and matching password of at least 12 characters');
  }
  const now = new Date().toISOString();
  await putAdmin({ username, passwordHash: hashSecret(password), createdAt: now, updatedAt: now });
  const response = jsonResponse({ ok: true, username });
  response.cookies.set({ ...sessionCookieOptions(), value: createSession(username) });
  return response;
}
