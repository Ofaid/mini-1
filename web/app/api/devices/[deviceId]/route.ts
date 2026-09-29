import { errorResponse, jsonResponse, readJson, requireAdmin, requireAdminMutation } from '@/lib/api';
import { recordAdminActivity, summarizeDeviceChange } from '@/lib/activity';
import { assertDeviceConfig, prepareConfigForSave, repairConfig } from '@/lib/config';
import { validDeviceId } from '@/lib/security';
import { validModelProfile } from '@/lib/model-profiles';
import { deleteDevice, deleteDeviceDeliveryStats, getDevice, getDeviceDeliveryStats, putDevice } from '@/lib/storage';
import type { MinimumConfig } from '@/lib/types';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

export async function GET(request: Request, context: Context) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  const device = await getDevice(deviceId);
  if (!device) return errorResponse('Device not found', 404);
  const deliveryStats = await getDeviceDeliveryStats(deviceId).catch(() => undefined);
  return jsonResponse({ device: { ...device, config: repairConfig(device.config, deviceId, device.model), ...(deliveryStats ? { deliveryStats } : {}) } });
}

export async function PATCH(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  const device = await getDevice(deviceId);
  if (!device) return errorResponse('Device not found', 404);
  const body = await readJson(request);
  const label = typeof body?.label === 'string' ? body.label.trim() : device.label;
  const model = typeof body?.model === 'string' ? body.model.trim() : device.model;
  if (!label || label.length > 128 || !validModelProfile(model)) return errorResponse('Label and a supported model profile are required');
  let config: MinimumConfig;
  try {
    if (!body?.config || typeof body.config !== 'object' || Array.isArray(body.config)) {
      return errorResponse('A configuration object is required');
    }
    const draft = { ...(body.config as Record<string, unknown>), modelProfile: model };
    config = assertDeviceConfig(prepareConfigForSave(draft, device.config, deviceId), deviceId);
  } catch (error) {
    return errorResponse(error instanceof Error ? error.message : 'Invalid configuration');
  }
  const updated = { ...device, label, model, config, updatedAt: new Date().toISOString() };
  await putDevice(updated);
  const change = summarizeDeviceChange(device, updated);
  if (change.sections.length > 0) await recordAdminActivity({
      action: 'device.updated',
      administrator: auth.username,
      resource: { type: 'device', id: deviceId, label, model },
      configVersions: { previous: device.config.configVersion, current: config.configVersion },
      change
    }).catch(() => undefined);
  const deliveryStats = await getDeviceDeliveryStats(deviceId).catch(() => undefined);
  return jsonResponse({ device: { ...updated, ...(deliveryStats ? { deliveryStats } : {}) } });
}

export async function DELETE(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  const device = await getDevice(deviceId);
  if (!device) return errorResponse('Device not found', 404);
  await deleteDevice(deviceId);
  await deleteDeviceDeliveryStats(deviceId).catch(() => undefined);
  await recordAdminActivity({
    action: 'device.deleted',
    administrator: auth.username,
    resource: { type: 'device', id: deviceId, label: device.label, model: device.model }
  }).catch(() => undefined);
  return jsonResponse({ ok: true });
}
