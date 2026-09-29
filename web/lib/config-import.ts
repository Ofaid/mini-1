import type { MinimumConfig } from './types';
import {
  clonePresetValue,
  normalizeChannelPath,
  normalizePresetChannelForImport,
  normalizePresetConnectionForImport
} from './config-presets';
import type {
  ConfigImportBundle,
  ConfigImportOptions,
  ConfigImportPreview,
  ConfigPresetChannel,
  ConfigPresetConnection,
  ConfigPresetSensitivePresence,
  ImportDecision,
  MinimumConfigWithCollections,
  SafeConfigImportBundle
} from './config-library-types';

type AnyRecord = Record<string, unknown>;
type TargetChannel = { raw: AnyRecord; normalized?: ConfigPresetChannel };
type Plan = {
  errors: string[];
  warnings: string[];
  decisions: ImportDecision[];
  connectionIdMap: Record<string, string>;
  channelIdMap: Record<string, string>;
  connections: Record<string, unknown>;
  channels: AnyRecord[];
};

function isRecord(value: unknown): value is AnyRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function has(value: AnyRecord, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function clone<T>(value: T): T {
  return clonePresetValue(value);
}

function targetConfig(value: MinimumConfig): MinimumConfigWithCollections {
  if (!isRecord(value)) throw new Error('target config must be an object');
  return value as MinimumConfigWithCollections;
}

function noConnectionId(value: ConfigPresetConnection): AnyRecord {
  const result = clone(value) as AnyRecord;
  delete result.id;
  return result;
}

function canonicalConnectionFromTarget(value: unknown, id: string): ConfigPresetConnection | undefined {
  try { return normalizePresetConnectionForImport(value, id); } catch { return undefined; }
}

function canonicalChannelFromTarget(value: unknown): ConfigPresetChannel | undefined {
  if (!isRecord(value) || typeof value.connectionId !== 'string') return undefined;
  try { return normalizePresetChannelForImport(value, value.connectionId); } catch { return undefined; }
}

function safePresenceForConnection(value: ConfigPresetConnection): Pick<ConfigPresetSensitivePresence, 'username' | 'password' | 'fingerprint'> {
  return {
    username: value.username !== undefined,
    password: value.password !== undefined,
    fingerprint: value.serverCertificateSha256 !== undefined
  };
}

function safePresenceForAccess(value: ConfigPresetChannel['access']): Pick<ConfigPresetSensitivePresence, 'publicTokens' | 'protectedTokenRef'> {
  return {
    publicTokens: value.mode === 'public' && Boolean(value.tokens?.length),
    protectedTokenRef: value.mode === 'protected' && value.tokenRef !== undefined
  };
}

function safeBundle(bundle: ConfigImportBundle): SafeConfigImportBundle {
  const connection = bundle.connection;
  return {
    schemaVersion: 1,
    connection: {
      id: connection.id,
      ...(connection.name === undefined ? {} : { name: connection.name }),
      host: connection.host,
      port: connection.port,
      ...(connection.autoTrustServerCertificate === undefined ? {} : { autoTrustServerCertificate: connection.autoTrustServerCertificate }),
      sensitive: safePresenceForConnection(connection)
    },
    channels: bundle.channels.map((channel) => ({
      id: channel.id,
      label: channel.label,
      ...(channel.alias === undefined ? {} : { alias: channel.alias }),
      connectionId: channel.connectionId,
      path: channel.path,
      ...(channel.presetKey === undefined ? {} : { presetKey: channel.presetKey }),
      access: { mode: channel.access.mode, sensitive: safePresenceForAccess(channel.access) }
    })),
    ...(bundle.defaultChannelId === undefined ? {} : { defaultChannelId: bundle.defaultChannelId })
  };
}

function normalizedBundle(value: unknown): ConfigImportBundle {
  if (!isRecord(value)) throw new Error('import bundle must be an object');
  const unknownKeys = Object.keys(value).filter((key) => !['schemaVersion', 'connection', 'channels', 'defaultChannelId'].includes(key));
  if (unknownKeys.length > 0) throw new Error(`${unknownKeys[0]} is not allowed in an import bundle`);
  if (value.schemaVersion !== 1) throw new Error('import bundle schemaVersion must be 1');
  if (!isRecord(value.connection) || typeof value.connection.id !== 'string') throw new Error('import bundle connection.id is required');
  const connection = normalizePresetConnectionForImport(value.connection, value.connection.id);
  if (!Array.isArray(value.channels) || value.channels.length < 1 || value.channels.length > 16) throw new Error('import bundle channels must contain 1 to 16 channels');
  const channels = value.channels.map((item, index) => {
    try { return normalizePresetChannelForImport(item, connection.id); }
    catch (error) { throw new Error(`channels[${index}] ${String(error).replace(/^Error: /, '')}`); }
  });
  const ids = new Set<string>();
  for (const channel of channels) {
    if (ids.has(channel.id)) throw new Error(`duplicate channel id ${channel.id}`);
    ids.add(channel.id);
  }
  let defaultChannelId: string | undefined;
  if (has(value, 'defaultChannelId')) {
    if (typeof value.defaultChannelId !== 'string' || !ids.has(value.defaultChannelId)) throw new Error('defaultChannelId must reference a bundle channel');
    defaultChannelId = value.defaultChannelId;
  }
  return { schemaVersion: 1, connection, channels, ...(defaultChannelId === undefined ? {} : { defaultChannelId }) };
}

function connectionEndpointKey(value: ConfigPresetConnection): string {
  return JSON.stringify({
    host: value.host,
    port: value.port,
    trust: value.autoTrustServerCertificate === undefined ? true : value.autoTrustServerCertificate,
    fingerprint: value.serverCertificateSha256 || ''
  });
}

function sameConnectionSemantics(left: ConfigPresetConnection, right: ConfigPresetConnection): boolean {
  return connectionEndpointKey(left) === connectionEndpointKey(right)
    && (left.username ?? undefined) === (right.username ?? undefined)
    && (left.password ?? undefined) === (right.password ?? undefined);
}

function sameHostPort(left: ConfigPresetConnection, right: ConfigPresetConnection): boolean {
  return left.host === right.host && left.port === right.port;
}

function optionConnectionAction(options: ConfigImportOptions): 'reuse' | 'add' | undefined {
  return options.connectionDuplicate;
}

function optionChannelAction(options: ConfigImportOptions): 'skip' | 'add' | 'replace' | undefined {
  return options.channelDuplicate;
}

function generateConflictId(base: string, used: Set<string>): string {
  const safeBase = base.length > 58 ? base.slice(0, 58) : base;
  if (!used.has(safeBase) && safeBase.length <= 64) return safeBase;
  for (let suffix = 2; suffix < 100000; suffix += 1) {
    const tail = `-${suffix}`;
    const candidate = `${base.slice(0, Math.max(1, 64 - tail.length))}${tail}`;
    if (!used.has(candidate)) return candidate;
  }
  throw new Error('unable to generate a conflict-free ID');
}

function targetChannels(value: MinimumConfigWithCollections): TargetChannel[] {
  const channels = Array.isArray(value.channels) ? value.channels : [];
  return channels.map((raw) => ({ raw: isRecord(raw) ? clone(raw) : {}, normalized: canonicalChannelFromTarget(raw) }));
}

function targetConnections(value: MinimumConfigWithCollections): Record<string, unknown> {
  return isRecord(value.connections) ? clone(value.connections) : {};
}

function addDecision(plan: Plan, decision: ImportDecision) {
  if (!plan.decisions.some((item) => item.kind === decision.kind && item.sourceId === decision.sourceId && item.targetId === decision.targetId)) {
    plan.decisions.push(decision);
  }
}

function planImport(target: MinimumConfig, bundle: ConfigImportBundle, options: ConfigImportOptions): Plan {
  const current = targetConfig(target);
  const plan: Plan = {
    errors: [], warnings: [], decisions: [], connectionIdMap: {}, channelIdMap: {},
    connections: targetConnections(current), channels: targetChannels(current).map((item) => item.raw)
  };
  const targetConnectionValues = new Map<string, ConfigPresetConnection>();
  for (const [id, raw] of Object.entries(plan.connections)) {
    const normalized = canonicalConnectionFromTarget(raw, id);
    if (normalized) targetConnectionValues.set(id, normalized);
  }

  if (bundle.channels.some((channel) => channel.access.mode === 'protected' && channel.access.tokenRef !== undefined)) {
    plan.warnings.push('import includes protected token references; the device must resolve them locally');
  }

  const usedConnectionIds = new Set(Object.keys(plan.connections));
  const sourceConnection = bundle.connection;
  let resolvedConnectionId: string | undefined;
  const sameId = targetConnectionValues.get(sourceConnection.id);
  if (sameId && sameConnectionSemantics(sourceConnection, sameId)) {
    resolvedConnectionId = sourceConnection.id;
  } else {
    const pinnedTarget = [...targetConnectionValues.entries()].find(([, candidate]) => sameHostPort(sourceConnection, candidate) && Boolean(candidate.serverCertificateSha256) && !sourceConnection.serverCertificateSha256);
    if (pinnedTarget) {
      const action = optionConnectionAction(options);
      if (action === 'reuse') {
        resolvedConnectionId = pinnedTarget[0];
        plan.warnings.push('reused a pinned target connection because the source omitted its fingerprint');
      } else {
        addDecision(plan, {
          kind: 'pinned-fingerprint', sourceId: sourceConnection.id, targetId: pinnedTarget[0],
          detail: 'Source omitted a pinned server fingerprint; include it or explicitly reuse the pinned target.',
          choices: ['reuse', 'include']
        });
      }
    }
    if (!resolvedConnectionId && !pinnedTarget) {
      const endpointDuplicate = [...targetConnectionValues.entries()].find(([, candidate]) => connectionEndpointKey(sourceConnection) === connectionEndpointKey(candidate));
      if (endpointDuplicate) {
        const action = optionConnectionAction(options);
        if (!action) {
          addDecision(plan, {
            kind: 'duplicate-connection', sourceId: sourceConnection.id, targetId: endpointDuplicate[0],
            detail: 'A target connection has the same host, port and trust settings.', choices: ['reuse', 'add']
          });
        } else if (action === 'reuse') resolvedConnectionId = endpointDuplicate[0];
        else resolvedConnectionId = generateConflictId(sourceConnection.id, usedConnectionIds);
      }
    }
    if (!resolvedConnectionId && !plan.decisions.some((decision) => decision.sourceId === sourceConnection.id && (decision.kind === 'pinned-fingerprint' || decision.kind === 'duplicate-connection'))) {
      resolvedConnectionId = usedConnectionIds.has(sourceConnection.id)
        ? generateConflictId(sourceConnection.id, usedConnectionIds)
        : sourceConnection.id;
    }
    if (resolvedConnectionId && resolvedConnectionId !== sourceConnection.id && !usedConnectionIds.has(resolvedConnectionId)) {
      usedConnectionIds.add(resolvedConnectionId);
    }
  }
  if (resolvedConnectionId) {
    plan.connectionIdMap[sourceConnection.id] = resolvedConnectionId;
    if (!has(plan.connections, resolvedConnectionId)) plan.connections[resolvedConnectionId] = noConnectionId({ ...sourceConnection, id: resolvedConnectionId });
  }
  if (Object.keys(plan.connections).length > 16) plan.errors.push('import would exceed the 16-connection capacity');

  const targetChannelsState = targetChannels(current);
  const usedChannelIds = new Set<string>(targetChannelsState.map((item) => item.normalized?.id).filter((id): id is string => Boolean(id)));
  const channelAction = optionChannelAction(options);
  const sourceToTargetConnection = resolvedConnectionId || sourceConnection.id;
  const importedDestinations: Array<{ source: ConfigPresetChannel; targetId: string; targetIndex?: number; mode: 'add' | 'replace' | 'skip'; raw: AnyRecord }> = [];
  for (const sourceChannel of bundle.channels) {
    const normalizedPath = normalizeChannelPath(sourceChannel.path);
    const duplicateIndex = targetChannelsState.findIndex((item) => item.normalized?.connectionId === sourceToTargetConnection && item.normalized.path === normalizedPath);
    if (duplicateIndex >= 0) {
      const duplicate = targetChannelsState[duplicateIndex].normalized;
      const action = channelAction;
      if (!action) {
        addDecision(plan, {
          kind: 'duplicate-channel', sourceId: sourceChannel.id, targetId: duplicate?.id,
          detail: 'A target channel already uses this connection and normalized path.', choices: ['skip', 'add', 'replace']
        });
        if (duplicate?.id) plan.channelIdMap[sourceChannel.id] = duplicate.id;
        continue;
      }
      if (action === 'skip') {
        if (duplicate?.id) plan.channelIdMap[sourceChannel.id] = duplicate.id;
        plan.warnings.push(`skipped duplicate channel ${sourceChannel.id}`);
        importedDestinations.push({ source: sourceChannel, targetId: duplicate?.id || sourceChannel.id, targetIndex: duplicateIndex, mode: 'skip', raw: targetChannelsState[duplicateIndex].raw });
        continue;
      }
      if (action === 'replace') {
        const targetId = duplicate?.id || sourceChannel.id;
        plan.channelIdMap[sourceChannel.id] = targetId;
        const raw = clone(sourceChannel) as AnyRecord;
        raw.id = targetId;
        raw.connectionId = sourceToTargetConnection;
        plan.channels[duplicateIndex] = raw;
        targetChannelsState[duplicateIndex] = { raw, normalized: { ...sourceChannel, id: targetId, connectionId: sourceToTargetConnection } };
        importedDestinations.push({ source: sourceChannel, targetId, targetIndex: duplicateIndex, mode: 'replace', raw });
        continue;
      }
    }
    const targetId = usedChannelIds.has(sourceChannel.id) ? generateConflictId(sourceChannel.id, usedChannelIds) : sourceChannel.id;
    usedChannelIds.add(targetId);
    const raw = clone(sourceChannel) as AnyRecord;
    raw.id = targetId;
    raw.connectionId = sourceToTargetConnection;
    const targetIndex = plan.channels.length;
    plan.channels.push(raw);
    targetChannelsState.push({ raw, normalized: { ...sourceChannel, id: targetId, connectionId: sourceToTargetConnection } });
    plan.channelIdMap[sourceChannel.id] = targetId;
    importedDestinations.push({ source: sourceChannel, targetId, targetIndex, mode: 'add', raw });
  }
  const presetKeyAction = options.presetKeyConflict;
  for (const destination of importedDestinations) {
    if (destination.mode === 'skip') continue;
    const sourceKey = destination.source.presetKey;
    if (!sourceKey) continue;
    const conflictingIndexes = targetChannelsState
      .map((item, index) => ({ item, index }))
      .filter(({ item, index }) => item.normalized?.presetKey === sourceKey && index !== destination.targetIndex)
      .map(({ index }) => index);
    if (conflictingIndexes.length === 0) continue;
    if (!presetKeyAction) {
      addDecision(plan, {
        kind: 'preset-key-conflict', sourceId: destination.targetId,
        detail: `Preset key ${sourceKey} is already assigned on another channel. Choose how to resolve it before applying.`,
        choices: ['unassign', 'unused', 'replace']
      });
      continue;
    }
    if (presetKeyAction === 'replace') {
      for (const index of conflictingIndexes) {
        delete plan.channels[index].presetKey;
        if (targetChannelsState[index].normalized) targetChannelsState[index].normalized = { ...targetChannelsState[index].normalized, presetKey: undefined };
      }
    } else {
      delete destination.raw.presetKey;
      const warningAction = presetKeyAction === 'unused' ? 'left unused' : 'unassigned';
      plan.warnings.push(`preset key ${sourceKey} was ${warningAction} on imported channel ${destination.targetId}`);
    }
  }
  if (plan.channels.length > 16) plan.errors.push('import would exceed the 16-channel capacity');
  return plan;
}

export type BuildImportSelection = {
  connectionId: string;
  channelIds?: string[];
  includeDefaultChannel?: boolean;
};

/** Build a source-only bundle. Device identity and device policy are never read into it. */
export function buildImportBundle(config: MinimumConfig, selection: BuildImportSelection): ConfigImportBundle {
  const source = targetConfig(config);
  const connections = isRecord(source.connections) ? source.connections : {};
  const rawConnection = connections[selection.connectionId];
  if (!rawConnection) throw new Error(`connection ${selection.connectionId} was not found`);
  const connection = normalizePresetConnectionForImport(rawConnection, selection.connectionId);
  const rawChannels = Array.isArray(source.channels) ? source.channels : [];
  const wanted = selection.channelIds ? new Set(selection.channelIds) : undefined;
  const channels: ConfigPresetChannel[] = [];
  for (const raw of rawChannels) {
    if (!isRecord(raw) || raw.connectionId !== selection.connectionId) continue;
    if (wanted && (typeof raw.id !== 'string' || !wanted.has(raw.id))) continue;
    channels.push(normalizePresetChannelForImport(raw, selection.connectionId));
  }
  if (channels.length < 1 || channels.length > 16) throw new Error('select between 1 and 16 channels for import');
  let defaultChannelId: string | undefined;
  if (selection.includeDefaultChannel) {
    const candidate = isRecord(source.radio) && typeof source.radio.defaultChannel === 'string' ? source.radio.defaultChannel : undefined;
    if (candidate && channels.some((channel) => channel.id === candidate)) defaultChannelId = candidate;
  }
  return {
    schemaVersion: 1,
    connection,
    channels: clone(channels),
    ...(defaultChannelId === undefined ? {} : { defaultChannelId })
  };
}

/** Return a safe plan; no credentials or token values are exposed in the result. */
export function previewConfigImport(target: MinimumConfig, source: ConfigImportBundle, options: ConfigImportOptions = {}): ConfigImportPreview {
  let bundle: ConfigImportBundle;
  try { bundle = normalizedBundle(source); }
  catch (error) {
    return {
      canApply: false, blocked: true, errors: [String(error).replace(/^Error: /, '')], warnings: [], decisions: [],
      source: { schemaVersion: 1, connection: { id: 'invalid', host: '', port: 1, sensitive: { username: false, password: false, fingerprint: false } }, channels: [] },
      connectionIdMap: {}, channelIdMap: {}, resultingCounts: { connections: 0, channels: 0 }
    };
  }
  let plan: Plan;
  try { plan = planImport(target, bundle, options); }
  catch (error) {
    return {
      canApply: false, blocked: true, errors: [String(error).replace(/^Error: /, '')], warnings: [], decisions: [],
      source: safeBundle(bundle), connectionIdMap: {}, channelIdMap: {}, resultingCounts: { connections: 0, channels: 0 }
    };
  }
  return {
    canApply: plan.errors.length === 0 && plan.decisions.length === 0,
    blocked: plan.errors.length > 0 || plan.decisions.length > 0,
    errors: clone(plan.errors),
    warnings: clone(plan.warnings),
    decisions: clone(plan.decisions),
    source: safeBundle(bundle),
    connectionIdMap: clone(plan.connectionIdMap),
    channelIdMap: clone(plan.channelIdMap),
    resultingCounts: { connections: Object.keys(plan.connections).length, channels: plan.channels.length }
  };
}

/** Apply an explicitly resolved import to a deep-cloned target draft. */
export function applyConfigImport(target: MinimumConfig, source: ConfigImportBundle, options: ConfigImportOptions = {}): MinimumConfig {
  const bundle = normalizedBundle(source);
  const plan = planImport(target, bundle, options);
  if (plan.errors.length > 0 || plan.decisions.length > 0) {
    const reasons = [...plan.errors, ...plan.decisions.map((decision) => decision.detail)];
    throw new Error(reasons.join('; '));
  }
  const draft = clone(target) as MinimumConfigWithCollections;
  draft.connections = clone(plan.connections);
  draft.channels = clone(plan.channels);
  if (options.importDefaultChannel && bundle.defaultChannelId) {
    const mapped = plan.channelIdMap[bundle.defaultChannelId];
    if (mapped) {
      const radio = isRecord(draft.radio) ? clone(draft.radio) : {};
      radio.defaultChannel = mapped;
      draft.radio = radio;
    }
  }
  return draft as MinimumConfig;
}

export { safeBundle as safeConfigImportBundle };
