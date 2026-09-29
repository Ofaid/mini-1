import { errorResponse, jsonResponse, readJson, requireAdmin, requireAdminMutation } from '../../../lib/api';
import { recordAdminActivity } from '../../../lib/activity';
import {
  ConfigPresetStoreError,
  createConfigPreset,
  listConfigPresets,
  safeConfigPresetRecord
} from '../../../lib/config-preset-storage';

export const runtime = 'nodejs';

function storeError(error: unknown) {
  if (error instanceof ConfigPresetStoreError) {
    if (error.code === 'conflict') return errorResponse(error.message, 409);
    if (error.code === 'capacity') return errorResponse(error.message, 409);
    if (error.code === 'invalid') return errorResponse('Invalid preset', 400);
    if (error.code === 'not-found') return errorResponse(error.message, 404);
  }
  return errorResponse('Preset store unavailable', 503);
}

function bodyPreset(body: Record<string, unknown> | null) {
  if (!body) return null;
  return body.preset && typeof body.preset === 'object' && !Array.isArray(body.preset)
    ? body.preset
    : body;
}

export async function GET(request: Request) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  try {
    const query = new URL(request.url).searchParams.get('q') || new URL(request.url).searchParams.get('search') || undefined;
    const records = await listConfigPresets(query);
    return jsonResponse({ presets: records.map(safeConfigPresetRecord) });
  } catch {
    return errorResponse('Preset store unavailable', 503);
  }
}

export async function POST(request: Request) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const body = await readJson(request);
  try {
    const record = await createConfigPreset(bodyPreset(body));
    await recordAdminActivity({
      action: 'preset.created',
      administrator: auth.username,
      resource: { type: 'preset', id: record.preset.id, label: record.preset.name },
      change: { sections: ['connections', 'channels'], connectionsAfter: 1, channelsAfter: record.preset.channels.length }
    }).catch(() => undefined);
    return jsonResponse({ preset: safeConfigPresetRecord(record) }, 201);
  } catch (error) {
    return storeError(error);
  }
}
