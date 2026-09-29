import { errorResponse, jsonResponse, readJson, requireAdminMutation } from '../../../../../../lib/api';
import { getDevice } from '../../../../../../lib/storage';
import { applyConfigImport, buildImportBundle } from '../../../../../../lib/config-import';
import { validDeviceId } from '../../../../../../lib/security';
import type { ConfigImportBundle, ConfigImportOptions, MinimumConfigWithCollections } from '../../../../../../lib/config-library-types';
import { getConfigPreset, releaseConfigPresetForImport } from '../../../../../../lib/config-preset-storage';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

function inclusionFromBody(body: Record<string, unknown> | null) {
  const source = body?.inclusion && typeof body.inclusion === 'object' && !Array.isArray(body.inclusion)
    ? body.inclusion as Record<string, unknown>
    : body || {};
  return {
    username: source.username === true,
    password: source.password === true,
    fingerprint: source.fingerprint === true,
    publicTokens: source.publicTokens === true,
    protectedTokenRef: source.protectedTokenRef === true
  };
}

function selectionFromBody(body: Record<string, unknown> | null) {
  const source = body?.selection && typeof body.selection === 'object' && !Array.isArray(body.selection)
    ? body.selection as Record<string, unknown>
    : body || {};
  return {
    connectionId: typeof source.connectionId === 'string' ? source.connectionId : undefined,
    channelIds: Array.isArray(source.channelIds) ? source.channelIds.filter((id): id is string => typeof id === 'string') : undefined,
    includeDefaultChannel: source.includeDefaultChannel === true
  };
}

function optionsFromBody(body: Record<string, unknown> | null): ConfigImportOptions {
  const source = body?.decisions && typeof body.decisions === 'object' && !Array.isArray(body.decisions)
    ? body.decisions as Record<string, unknown>
    : body || {};
  const options: ConfigImportOptions = {};
  if (source.connectionDuplicate === 'reuse' || source.connectionDuplicate === 'add') options.connectionDuplicate = source.connectionDuplicate;
  if (source.channelDuplicate === 'skip' || source.channelDuplicate === 'add' || source.channelDuplicate === 'replace') options.channelDuplicate = source.channelDuplicate;
  if (source.presetKeyConflict === 'unassign' || source.presetKeyConflict === 'unused' || source.presetKeyConflict === 'replace') options.presetKeyConflict = source.presetKeyConflict;
  if (source.importDefaultChannel === true) options.importDefaultChannel = true;
  return options;
}

function sourceBundle(body: Record<string, unknown> | null, sourcePreset: ConfigImportBundle | null): ConfigImportBundle {
  if (sourcePreset) return sourcePreset;
  if (body?.sourceBundle && typeof body.sourceBundle === 'object' && !Array.isArray(body.sourceBundle)) return body.sourceBundle as ConfigImportBundle;
  throw new Error('A source preset or source bundle is required');
}

function sanitizeBundle(bundle: ConfigImportBundle, inclusion: ReturnType<typeof inclusionFromBody>): ConfigImportBundle {
  const connection = { ...bundle.connection };
  if (!inclusion.username) delete connection.username;
  if (!inclusion.password) delete connection.password;
  if (!inclusion.fingerprint) delete connection.serverCertificateSha256;
  const channels = bundle.channels.map((channel) => {
    const access = { ...channel.access };
    if (access.mode === 'public' && !inclusion.publicTokens) {
      delete access.tokens;
      delete access.token;
    }
    if (access.mode === 'protected' && !inclusion.protectedTokenRef) delete access.tokenRef;
    if (access.mode !== 'public') delete access.tokens;
    if (access.mode !== 'protected') delete access.tokenRef;
    return { ...channel, access };
  });
  return { ...bundle, connection, channels };
}

export async function POST(request: Request, context: Context) {
  const auth = await requireAdminMutation(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  const device = await getDevice(deviceId);
  if (!device) return errorResponse('Device not found', 404);
  const body = await readJson(request);
  try {
    const target = (body?.targetDraft && typeof body.targetDraft === 'object' && !Array.isArray(body.targetDraft)
      ? body.targetDraft
      : device.config) as MinimumConfigWithCollections;
    const selection = selectionFromBody(body);
    let presetBundle: ConfigImportBundle | null = null;
    if (typeof body?.presetId === 'string') {
      const record = await getConfigPreset(body.presetId);
      if (!record) return errorResponse('Preset not found', 404);
      // Secret values are released solely according to explicit apply inclusion choices.
      const released = releaseConfigPresetForImport(record, inclusionFromBody(body));
      const channels = selection.channelIds
        ? released.channels.filter((channel) => selection.channelIds!.includes(channel.id))
        : released.channels;
      if (channels.length === 0) return errorResponse('At least one source channel is required', 400);
      presetBundle = {
        schemaVersion: 1,
        connection: released.connection,
        channels,
        ...(selection.includeDefaultChannel && typeof body.defaultChannelId === 'string' ? { defaultChannelId: body.defaultChannelId } : {})
      };
    }
    if (!presetBundle && typeof body?.sourceDeviceId === 'string') {
      if (!validDeviceId(body.sourceDeviceId)) return errorResponse('Invalid source device ID', 400);
      const sourceDevice = await getDevice(body.sourceDeviceId);
      if (!sourceDevice) return errorResponse('Source device not found', 404);
      const connectionId = typeof body.connectionId === 'string' ? body.connectionId : '';
      const resolvedConnectionId = connectionId || selection.connectionId || '';
      if (!resolvedConnectionId) return errorResponse('connectionId is required for source device', 400);
      presetBundle = buildImportBundle(sourceDevice.config, {
        connectionId: resolvedConnectionId,
        channelIds: selection.channelIds,
        includeDefaultChannel: selection.includeDefaultChannel
      });
    }
    let source = sourceBundle(body, presetBundle);
    source = sanitizeBundle(source, inclusionFromBody(body));
    const options = optionsFromBody(body);
    const draft = applyConfigImport(target as never, source, options);
    return jsonResponse({ draft }, 200, { 'Cache-Control': 'no-store' });
  } catch (error) {
    return errorResponse('Invalid import request', 400);
  }
}
