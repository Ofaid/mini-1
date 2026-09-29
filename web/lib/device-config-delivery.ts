import { errorResponse, jsonResponse } from './api';
import { recordConfigRequestActivity, recordThrottledConfigRequestActivity } from './activity';
import { effectiveConfigsEqual, prepareConfigForSave } from './config';
import { validDeviceId } from './security';
import {
  clearDismissedPendingDevice,
  getDevice,
  putDevice,
  recordDeviceConfigRequest,
  recordPendingDeviceRequest
} from './storage';

// Public configuration delivery must not wait indefinitely on advisory
// counters/activity writes. The route runs in the Node runtime, so a short
// timer race is portable and lets unfinished writes continue in the background
// without creating unhandled rejections.
const DELIVERY_TELEMETRY_TIMEOUT_MS = 200;

async function flushDeliveryTelemetry(tasks: Array<Promise<unknown>>) {
  const allTasks = Promise.allSettled(tasks).then(() => undefined);
  let timeout: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<void>((resolve) => {
    timeout = setTimeout(resolve, DELIVERY_TELEMETRY_TIMEOUT_MS);
  });
  try {
    await Promise.race([allTasks, deadline]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

function advisoryTask(task: () => Promise<unknown>) {
  // Start each advisory operation in a microtask so a synchronous throw is
  // captured by allSettled just like an asynchronous rejection.
  return Promise.resolve().then(task);
}

function notFoundResponse() {
  return errorResponse('Not found', 404);
}

/**
 * Deliver a managed device configuration or record an unknown inbound request.
 * Both public route aliases use this helper so their lookup, repair and 404
 * behavior cannot drift apart.
 */
export async function deliverDeviceConfig(deviceId: string) {
  if (!validDeviceId(deviceId)) {
    // Keep malformed identifiers indistinguishable from unknown devices and
    // never persist the caller's raw value in activity storage.
    await flushDeliveryTelemetry([
      advisoryTask(() => recordThrottledConfigRequestActivity({ result: 'invalid' }, 'invalid-device-id'))
    ]);
    return notFoundResponse();
  }

  let device;
  try {
    device = await getDevice(deviceId);
  } catch {
    return errorResponse('Device configuration unavailable', 503);
  }
  if (!device) {
    // Pending bookkeeping is advisory. It must never make an unknown lookup
    // distinguishable from another public 404 when storage is unavailable.
    await flushDeliveryTelemetry([
      advisoryTask(() => recordPendingDeviceRequest(deviceId)),
      advisoryTask(() => recordThrottledConfigRequestActivity({ result: 'not-found', deviceId }, `unknown-device:${deviceId}`))
    ]);
    return notFoundResponse();
  }

  // A device record is authoritative over an old dismissal marker. Cleanup
  // is best effort because Cloudflare KV is eventually consistent; delivery
  // must remain available even if this stale-key delete is unavailable.
  await flushDeliveryTelemetry([
    advisoryTask(() => clearDismissedPendingDevice(deviceId))
  ]);

  const config = prepareConfigForSave(
    { ...(device.config as Record<string, unknown>), modelProfile: device.model },
    device.config,
    device.deviceId
  );
  if (config.configVersion !== device.config.configVersion || !effectiveConfigsEqual(config, device.config)) {
    try {
      await putDevice({ ...device, config, updatedAt: new Date().toISOString() });
    } catch {
      return errorResponse('Device configuration unavailable', 503);
    }
  }
  // Telemetry is deliberately best effort and must never alter the served
  // response. Dispatch both writes together and wait only for a short,
  // bounded deadline; a slow KV provider cannot hold a public config request.
  await flushDeliveryTelemetry([
    advisoryTask(() => recordDeviceConfigRequest(deviceId, {
      profileCreatedAt: device.createdAt,
      served: true,
      configVersionServed: config.configVersion
    })),
    advisoryTask(() => recordConfigRequestActivity({
      result: 'served',
      deviceId,
      label: device.label,
      model: device.model,
      configVersion: config.configVersion
    }))
  ]);
  return jsonResponse(config);
}
