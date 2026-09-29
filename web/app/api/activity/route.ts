import { errorResponse, jsonResponse, requireAdmin } from '@/lib/api';
import { listActivityPage, type ActivityFilters } from '@/lib/activity';
import type { ActivityAction, ActivityCategory, ActivityResult } from '@/lib/types';
import { validDeviceId, validUsername } from '@/lib/security';

export const runtime = 'nodejs';

const categories = new Set<ActivityCategory>(['administrator', 'device-configuration', 'system']);
const results = new Set<ActivityResult>(['succeeded', 'served', 'not-found', 'invalid', 'failed']);
const actions = new Set<ActivityAction>([
  'admin.login.succeeded', 'admin.logout', 'device.created', 'device.updated', 'device.deleted',
  'pending-request.dismissed', 'preset.created', 'preset.updated', 'preset.deleted',
  'config.request.succeeded', 'config.request.unknown-device', 'config.request.invalid-device-id',
  'config.request.failed', 'storage.unavailable', 'configuration.validation.failed', 'activity-log.write.failed'
]);

function parseDate(value: string | null, name: string) {
  if (value === null) return undefined;
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) throw new Error(`Invalid ${name}`);
  return new Date(millis).toISOString();
}
function parseFilters(request: Request) {
  const query = new URL(request.url).searchParams;
  const from = parseDate(query.get('from'), 'from');
  const to = parseDate(query.get('to'), 'to');
  if (from && to && Date.parse(from) > Date.parse(to)) throw new Error('from must be before to');
  const deviceId = query.get('deviceId') || undefined;
  if (deviceId && !validDeviceId(deviceId)) throw new Error('Invalid deviceId');
  const label = query.get('label') || undefined;
  if (label && (label.length > 128 || label.trim() !== label)) throw new Error('Invalid label');
  const administrator = query.get('administrator') || undefined;
  if (administrator && !validUsername(administrator)) throw new Error('Invalid administrator');
  const category = query.get('category') || undefined;
  if (category && !categories.has(category as ActivityCategory)) throw new Error('Invalid category');
  const action = query.get('action') || undefined;
  if (action && !actions.has(action as ActivityAction)) throw new Error('Invalid action');
  const result = query.get('result') || undefined;
  if (result && !results.has(result as ActivityResult)) throw new Error('Invalid result');
  const model = query.get('model') || undefined;
  if (model && (model.length > 64 || !/^[A-Za-z0-9._-]+$/.test(model))) throw new Error('Invalid model');
  const configVersionRaw = query.get('configVersion');
  let configVersion: number | undefined;
  if (configVersionRaw !== null) {
    if (!/^\d+$/.test(configVersionRaw)) throw new Error('Invalid configVersion');
    configVersion = Number(configVersionRaw);
    if (!Number.isSafeInteger(configVersion)) throw new Error('Invalid configVersion');
  }
  const limitRaw = query.get('limit');
  let limit: number | undefined;
  if (limitRaw !== null) {
    if (!/^\d+$/.test(limitRaw)) throw new Error('Invalid limit');
    limit = Number(limitRaw);
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new Error('Invalid limit');
  }
  const cursor = query.get('cursor') || undefined;
  const filters: ActivityFilters = {
    ...(from ? { from } : {}), ...(to ? { to } : {}), ...(deviceId ? { deviceId } : {}),
    ...(label ? { label } : {}), ...(administrator ? { administrator } : {}),
    ...(category ? { category: category as ActivityCategory } : {}),
    ...(action ? { action: action as ActivityAction } : {}), ...(result ? { result: result as ActivityResult } : {}),
    ...(model ? { model } : {}), ...(configVersion !== undefined ? { configVersion } : {})
  };
  return { filters, limit, cursor };
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  try {
    const parsed = parseFilters(request);
    const page = await listActivityPage(parsed.filters, { limit: parsed.limit, cursor: parsed.cursor });
    return jsonResponse({ events: page.events, ...(page.nextCursor ? { nextCursor: page.nextCursor } : {}) });
  } catch (error) {
    if (error instanceof Error && /^Invalid |^from must/.test(error.message)) return errorResponse(error.message, 400);
    return errorResponse('Activity store unavailable', 503);
  }
}
