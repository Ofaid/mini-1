import { errorResponse, jsonResponse, requireAdmin } from '@/lib/api';
import { listPendingDeviceRequests } from '@/lib/storage';

export const runtime = 'nodejs';

export async function GET(request: Request) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  try {
    return jsonResponse({ requests: await listPendingDeviceRequests() });
  } catch {
    return errorResponse('Device request store unavailable', 503);
  }
}
