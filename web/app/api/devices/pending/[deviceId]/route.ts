import { errorResponse, jsonResponse, requireAdminMutation } from '../../../../../lib/api';
import { recordAdminActivity } from '../../../../../lib/activity';
import { dismissPendingDeviceRequest } from '../../../../../lib/storage';
import { validDeviceId } from '../../../../../lib/security';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

export async function DELETE(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  try {
    await dismissPendingDeviceRequest(deviceId);
    await recordAdminActivity({
      action: 'pending-request.dismissed',
      administrator: auth.username,
      resource: { type: 'pending-device', id: deviceId }
    }).catch(() => undefined);
    return jsonResponse({ ok: true, deviceId });
  } catch {
    return errorResponse('Device request store unavailable', 503);
  }
}
