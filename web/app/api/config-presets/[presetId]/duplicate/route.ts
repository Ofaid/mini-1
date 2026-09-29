import { errorResponse, jsonResponse, readJson, requireAdmin, requireAdminMutation } from '../../../../../lib/api';
import { recordAdminActivity } from '../../../../../lib/activity';
import {
  ConfigPresetStoreError,
  createConfigPreset,
  getConfigPreset,
  safeConfigPresetRecord
} from '../../../../../lib/config-preset-storage';
import { clonePresetValue, validPresetId } from '../../../../../lib/config-presets';

export const runtime = 'nodejs';

type Context = { params: Promise<{ presetId: string }> };

function storeError(error: unknown) {
  if (error instanceof ConfigPresetStoreError) {
    if (error.code === 'conflict' || error.code === 'capacity') return errorResponse(error.message, 409);
    if (error.code === 'invalid') return errorResponse('Invalid preset', 400);
    if (error.code === 'not-found') return errorResponse(error.message, 404);
  }
  return errorResponse('Preset store unavailable', 503);
}

export async function POST(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { presetId } = await context.params;
  const source = await getConfigPreset(presetId);
  if (!source) return errorResponse('Preset not found', 404);
  const body = await readJson(request);
  const expectedUpdatedAt = typeof body?.expectedUpdatedAt === 'string'
    ? body.expectedUpdatedAt
    : request.headers.get('if-match')?.replace(/^"|"$/g, '') || undefined;
  if (!expectedUpdatedAt) return errorResponse('expectedUpdatedAt is required', 428);
  if (expectedUpdatedAt !== source.updatedAt) return errorResponse('Preset was updated by another request', 409);
  const newId = typeof body?.id === 'string' ? body.id.trim() : typeof body?.newId === 'string' ? body.newId.trim() : '';
  const newName = typeof body?.name === 'string' ? body.name.trim() : typeof body?.newName === 'string' ? body.newName.trim() : `${source.preset.name} copy`;
  if (!validPresetId(newId) || !newName || newName.length > 128) return errorResponse('A valid duplicate ID and name are required', 400);
  const duplicate = clonePresetValue(source.preset);
  duplicate.id = newId;
  duplicate.name = newName;
  try {
    const record = await createConfigPreset(duplicate);
    await recordAdminActivity({
      action: 'preset.created', administrator: auth.username,
      resource: { type: 'preset', id: record.preset.id, label: record.preset.name },
      change: { sections: ['connections', 'channels'], connectionsAfter: 1, channelsAfter: record.preset.channels.length }
    }).catch(() => undefined);
    return jsonResponse({ preset: safeConfigPresetRecord(record) }, 201);
  } catch (error) {
    return storeError(error);
  }
}
