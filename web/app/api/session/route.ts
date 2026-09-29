import { getAdmin } from '@/lib/storage';
import { jsonResponse } from '@/lib/api';
import { sessionFromRequest } from '@/lib/api';

export const runtime = 'nodejs';

export async function GET(request: Request) {
  const admin = await getAdmin();
  const session = sessionFromRequest(request);
  return jsonResponse({
    configured: Boolean(admin),
    authenticated: Boolean(admin && session && admin.username === session.username),
    username: admin && session && admin.username === session.username ? session.username : null
  });
}
