import { errorResponse, jsonResponse, readJson, requireAdmin, requireAdminMutation } from '../../../../lib/api';
import { recordAdminActivity } from '../../../../lib/activity';
import {
  ConfigPresetStoreError,
  deleteConfigPreset,
  getConfigPreset,
  safeConfigPresetRecord,
  updateConfigPreset
} from '../../../../lib/config-preset-storage';

export const runtime = 'nodejs';

type Context = { params: Promise<{ presetId: string }> };

function storeError(error: unknown) {
  if (error instanceof ConfigPresetStoreError) {
    if (error.code === 'conflict') return errorResponse(error.message, 409);
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

export async function GET(request: Request, context: Context) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  const { presetId } = await context.params;
  try {
    const record = await getConfigPreset(presetId);
    if (!record) return errorResponse('Preset not found', 404);
    return jsonResponse({ preset: safeConfigPresetRecord(record) });
  } catch {
    return errorResponse('Preset store unavailable', 503);
  }
}

export async function PATCH(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { presetId } = await context.params;
  const body = await readJson(request);
  const expectedUpdatedAt = typeof body?.expectedUpdatedAt === 'string'
    ? body.expectedUpdatedAt
    : request.headers.get('if-match')?.replace(/^"|"$/g, '') || undefined;
  if (!expectedUpdatedAt) return errorResponse('expectedUpdatedAt is required', 428);
  const secretPolicy = body?.secretPolicy && typeof body.secretPolicy === 'object' && !Array.isArray(body.secretPolicy) ? body.secretPolicy : undefined;
  try {
    const record = await updateConfigPreset(presetId, bodyPreset(body), expectedUpdatedAt, new Date().toISOString(), secretPolicy);
    await recordAdminActivity({
      action: 'preset.updated',
      administrator: auth.username,
      resource: { type: 'preset', id: record.preset.id, label: record.preset.name },
      change: { sections: ['connections', 'channels'], connectionsAfter: 1, channelsAfter: record.preset.channels.length }
    }).catch(() => undefined);
    return jsonResponse({ preset: safeConfigPresetRecord(record) });
  } catch (error) {
    return storeError(error);
  }
}

export async function DELETE(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { presetId } = await context.params;
  let expectedUpdatedAt: string | undefined;
  try {
    const queryValue = new URL(request.url).searchParams.get('expectedUpdatedAt');
    expectedUpdatedAt = queryValue || undefined;
  } catch {
    // Request URLs are validated by the runtime; body fallback remains available.
  }
  if (!expectedUpdatedAt) {
    const body = await readJson(request);
    expectedUpdatedAt = typeof body?.expectedUpdatedAt === 'string' ? body.expectedUpdatedAt : undefined;
  }
  if (!expectedUpdatedAt) {
    expectedUpdatedAt = request.headers.get('if-match')?.replace(/^"|"$/g, '') || undefined;
  }
  if (!expectedUpdatedAt) return errorResponse('expectedUpdatedAt is required', 428);
  try {
    const current = await getConfigPreset(presetId);
    if (!current) return errorResponse('Preset not found', 404);
    await deleteConfigPreset(presetId, expectedUpdatedAt);
    await recordAdminActivity({
      action: 'preset.deleted',
      administrator: auth.username,
      resource: { type: 'preset', id: presetId, label: current.preset.name },
      change: { sections: ['connections', 'channels'], connectionsBefore: 1, channelsBefore: current.preset.channels.length }
    }).catch(() => undefined);
    return jsonResponse({ ok: true });
  } catch (error) {
    return storeError(error);
  }
}
