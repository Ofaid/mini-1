import { errorResponse, jsonResponse, readJson, requireAdmin } from '../../../../../../lib/api';
import { getDevice } from '../../../../../../lib/storage';
import { previewConfigImport, buildImportBundle } from '../../../../../../lib/config-import';
import { redactConfigPreset, validateConfigPreset } from '../../../../../../lib/config-presets';
import { getConfigPreset, releaseConfigPresetForImport } from '../../../../../../lib/config-preset-storage';
import { validDeviceId } from '../../../../../../lib/security';
import type { ConfigImportOptions, MinimumConfigWithCollections } from '../../../../../../lib/config-library-types';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

function sourceFromBody(body: Record<string, unknown> | null, target: MinimumConfigWithCollections) {
  if (body?.preset && typeof body.preset === 'object' && !Array.isArray(body.preset)) return body.preset;
  if (body?.sourceBundle && typeof body.sourceBundle === 'object' && !Array.isArray(body.sourceBundle)) return body.sourceBundle;
  if (body?.sourceConfig && typeof body.sourceConfig === 'object' && !Array.isArray(body.sourceConfig)) {
    const sourceConfig = body.sourceConfig as MinimumConfigWithCollections;
    const connectionId = body.connectionId;
    const channelIds = body.channelIds;
    if (typeof connectionId !== 'string') throw new Error('connectionId is required for sourceConfig');
    return buildImportBundle(sourceConfig, {
      connectionId,
      channelIds: Array.isArray(channelIds) ? channelIds.filter((id): id is string => typeof id === 'string') : undefined,
      includeDefaultChannel: body.includeDefaultChannel === true
    });
  }
  if (body?.device && typeof body.device === 'object' && !Array.isArray(body.device)) {
    const sourceDevice = body.device as Record<string, unknown>;
    const sourceConfig = sourceDevice.config as MinimumConfigWithCollections;
    const connectionId = body.connectionId;
    if (typeof connectionId !== 'string') throw new Error('connectionId is required for source device');
    return buildImportBundle(sourceConfig, {
      connectionId,
      channelIds: Array.isArray(body.channelIds) ? body.channelIds.filter((id): id is string => typeof id === 'string') : undefined,
      includeDefaultChannel: body.includeDefaultChannel === true
    });
  }
  throw new Error('A preset, source bundle, source config or source device is required');
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

function targetFromBody(body: Record<string, unknown> | null, device: Record<string, unknown>) {
  const candidate = body?.targetDraft || body?.targetConfig;
  if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) return candidate as MinimumConfigWithCollections;
  return device.config as MinimumConfigWithCollections;
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

export async function POST(request: Request, context: Context) {
  const auth = await requireAdmin(request);
  if ('response' in auth) return auth.response;
  const { deviceId } = await context.params;
  if (!validDeviceId(deviceId)) return errorResponse('Invalid device ID', 400);
  const device = await getDevice(deviceId);
  if (!device) return errorResponse('Device not found', 404);
  let body = await readJson(request);
  try {
    const target = targetFromBody(body, device as unknown as Record<string, unknown>);
    const selection = selectionFromBody(body);
    if (typeof body?.sourceDeviceId === 'string') {
      if (!validDeviceId(body.sourceDeviceId)) return errorResponse('Invalid source device ID', 400);
      const sourceDevice = await getDevice(body.sourceDeviceId);
      if (!sourceDevice) return errorResponse('Source device not found', 404);
      body = {
        ...body,
        sourceConfig: sourceDevice.config,
        connectionId: selection.connectionId,
        channelIds: selection.channelIds,
        includeDefaultChannel: selection.includeDefaultChannel
      };
      delete body.device;
    }
    if (typeof body?.presetId === 'string') {
      const record = await getConfigPreset(body.presetId);
      if (!record) return errorResponse('Preset not found', 404);
      const released = releaseConfigPresetForImport(record, inclusionFromBody(body));
      const channels = selection.channelIds
        ? released.channels.filter((channel) => selection.channelIds!.includes(channel.id))
        : released.channels;
      if (channels.length === 0) return errorResponse('At least one source channel is required', 400);
      body = {
        ...body,
        sourceBundle: {
          schemaVersion: 1,
          connection: released.connection,
          channels,
          ...(selection.includeDefaultChannel && typeof body.defaultChannelId === 'string' ? { defaultChannelId: body.defaultChannelId } : {})
        }
      };
    }
    if (body?.sourceConfig && selection.connectionId) {
      body = {
        ...body,
        connectionId: selection.connectionId,
        channelIds: selection.channelIds,
        includeDefaultChannel: selection.includeDefaultChannel
      };
    }
    if (body?.preset && typeof body.preset === 'object' && !Array.isArray(body.preset)) {
      const released = redactConfigPreset(body.preset as never, inclusionFromBody(body));
      body = {
        ...body,
        sourceBundle: { schemaVersion: 1, connection: released.connection, channels: released.channels },
      };
      delete body.preset;
    }
    const source = sourceFromBody(body, target);
    const presetValidation = source && typeof source === 'object' && 'schemaVersion' in source && 'id' in source && 'name' in source
      ? validateConfigPreset(source)
      : null;
    if (presetValidation && !presetValidation.valid) return errorResponse(presetValidation.errors.join('; '), 400);
    const preview = previewConfigImport(target as never, source as never, optionsFromBody(body));
    return jsonResponse({ preview }, 200, { 'Cache-Control': 'no-store' });
  } catch (error) {
    return errorResponse('Invalid import request', 400);
  }
}
