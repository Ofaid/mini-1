import { errorResponse, jsonResponse, readJson, requireAdmin, requireAdminMutation } from '@/lib/api';
import { recordAdminActivity } from '@/lib/activity';
import { assertDeviceConfig, emptyConfig, repairConfig } from '@/lib/config';
import { validDeviceId } from '@/lib/security';
import { validModelProfile } from '@/lib/model-profiles';
import { clearDismissedPendingDevice, deletePendingDeviceRequest, getDevice, listDevices, putDevice } from '@/lib/storage';
import type { DeviceSummary, MinimumConfig, StoredDevice } from '@/lib/types';

export const runtime = 'nodejs';

function summary(device: StoredDevice): DeviceSummary {
  return {
    deviceId: device.deviceId,
    label: device.label,
    model: device.model,
    configVersion: device.config.configVersion,
    createdAt: device.createdAt,
    updatedAt: device.updatedAt
  };
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  try {
    return jsonResponse({ devices: (await listDevices()).map(summary) });
  } catch {
    return errorResponse('Device store unavailable', 503);
  }
}

export async function POST(request: Request) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const body = await readJson(request);
  const deviceId = typeof body?.deviceId === 'string' ? body.deviceId.toUpperCase() : '';
  const label = typeof body?.label === 'string' ? body.label.trim() : '';
  const model = typeof body?.model === 'string' ? body.model.trim() : '';
  if (!validDeviceId(deviceId) || !label || label.length > 128 || !validModelProfile(model)) {
    return errorResponse('Device ID, label and a supported model profile are required');
  }
  if (await getDevice(deviceId)) return errorResponse('Device already exists', 409);
  let config: MinimumConfig;
  try {
    config = assertDeviceConfig(repairConfig(body?.config || emptyConfig(deviceId, model), deviceId, model), deviceId);
  } catch (error) {
    return errorResponse(error instanceof Error ? error.message : 'Invalid configuration');
  }
  const now = new Date().toISOString();
  const device: StoredDevice = {
    deviceId,
    label,
    model,
    config,
    createdAt: now,
    updatedAt: now
  };
  try {
    await putDevice(device);
    // The device write is authoritative. Pending/marker cleanup is advisory
    // under Cloudflare KV eventual consistency and must not undo registration;
    // registered config delivery also self-heals any stale marker later.
    await Promise.all([
      deletePendingDeviceRequest(deviceId),
      clearDismissedPendingDevice(deviceId)
    ].map((operation) => operation.catch(() => undefined)));
    await recordAdminActivity({
      action: 'device.created',
      administrator: auth.username,
      resource: { type: 'device', id: deviceId, label, model },
      configVersions: { current: config.configVersion }
    }).catch(() => undefined);
    return jsonResponse({ device: summary(device) }, 201);
  } catch {
    return errorResponse('Device store unavailable', 503);
  }
}
