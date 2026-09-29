import { errorResponse, jsonResponse, requireAdmin } from '@/lib/api';
import { listActivityPage } from '@/lib/activity';
import { buildOverview } from '@/lib/overview';
import {
  checkStorageHealth,
  getDeviceDeliveryStats,
  listDevices,
  listPendingDeviceRequests
} from '@/lib/storage';

export const runtime = 'nodejs';

async function collectAdminActivity(from?: string) {
  const events = [] as Awaited<ReturnType<typeof listActivityPage>>['events'];
  let cursor: string | undefined;
  // Activity is bounded by the storage reader's safety limit. Continue through
  // opaque cursors so the 24-hour change count is not capped at one page.
  for (let pageNumber = 0; pageNumber < 10; pageNumber += 1) {
    const page = await listActivityPage({ category: 'administrator', ...(from ? { from } : {}) }, { limit: 100, cursor });
    events.push(...page.events);
    if (!page.nextCursor) break;
    cursor = page.nextCursor;
  }
  return events;
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  try {
    const now = Date.now();
    const from24h = new Date(now - 24 * 60 * 60 * 1000).toISOString();
    const devices = await listDevices();
    const [pending, storage, deliveryStats, recentAdminActivity, adminActivity24h] = await Promise.all([
      listPendingDeviceRequests(),
      checkStorageHealth(),
      // Delivery telemetry is deliberately read separately from the device
      // record; absent stats simply mean that no successful response is known.
      Promise.all(devices.map(async (device) => [device.deviceId, await getDeviceDeliveryStats(device.deviceId)] as const)),
      listActivityPage({ category: 'administrator' }, { limit: 20 }),
      collectAdminActivity(from24h)
    ]);
    const stats = Object.fromEntries(deliveryStats);
    const configChanges24h = adminActivity24h.filter((event) => event.action === 'device.created' || (event.action === 'device.updated' && (event.change?.sections?.length || 0) > 0));
    return jsonResponse(buildOverview({
      now,
      devices,
      pending,
      deliveryStats: stats,
      configChanges24h,
      recentAdminActivity: recentAdminActivity.events,
      storage
    }));
  } catch {
    // Do not return partial cards: an overview assembled from mixed snapshots
    // could falsely imply that delivery/storage is healthy.
    return errorResponse('Overview unavailable', 503);
  }
}
