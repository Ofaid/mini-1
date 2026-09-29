import { jsonResponse, requireAdminMutation } from '../../../lib/api';
import { recordAdminActivity } from '../../../lib/activity';
import { sessionCookieName } from '../../../lib/security';

export const runtime = 'nodejs';

export async function POST(request: Request) {
  // Require the same session, same-origin and BotID checks as every other
  // state-changing admin route. Invalid callers must not receive a clearing
  // cookie, while a valid session remains safely idempotent.
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;

  const response = jsonResponse({ ok: true });
  response.cookies.set({ name: sessionCookieName(), value: '', httpOnly: true, sameSite: 'strict', secure: process.env.NODE_ENV === 'production', path: '/', maxAge: 0 });
  // Audit is advisory; a storage failure must not turn a successful logout
  // into a server error or expose provider details.
  await recordAdminActivity({ action: 'admin.logout', administrator: auth.username, resource: { type: 'system' } }).catch(() => undefined);
  return response;
}
