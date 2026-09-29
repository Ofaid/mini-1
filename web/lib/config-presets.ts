import type { MinimumConfig } from './types';
import type {
  ConfigPresetAccess,
  ConfigPresetChannel,
  ConfigPresetConnection,
  ConfigPresetExportOptions,
  ConfigPresetOmissionFlags,
  ConfigPresetSecretInclusion,
  ConfigPresetSensitivePresence,
  ConfigPresetValidationResult,
  MinimumConfigWithCollections,
  PresetSelection,
  SafeConfigPreset,
  StoredConfigPreset
} from './config-library-types';

const CONNECTION_KEYS = [
  'id', 'name', 'host', 'port', 'username', 'password',
  'serverCertificateSha256', 'autoTrustServerCertificate'
] as const;
const CHANNEL_KEYS = ['id', 'label', 'alias', 'connectionId', 'path', 'presetKey', 'access'] as const;
const ACCESS_KEYS = ['mode', 'token', 'tokens', 'tokenRef'] as const;
const OMISSION_KEYS = ['username', 'password', 'fingerprint', 'publicTokens', 'protectedTokenRef'] as const;

type AnyRecord = Record<string, unknown>;

function isRecord(value: unknown): value is AnyRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function has(value: AnyRecord, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

/** Clone only JSON-shaped data, retaining undefined only when it is a scalar input. */
export function clonePresetValue<T>(value: T): T {
  if (Array.isArray(value)) return value.map((item) => clonePresetValue(item)) as T;
  if (isRecord(value)) {
    const result: AnyRecord = {};
    for (const [key, nested] of Object.entries(value)) result[key] = clonePresetValue(nested);
    return result as T;
  }
  return value;
}

function rejectUnknown(value: AnyRecord, allowed: readonly string[], path: string, errors: string[]) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) errors.push(`${path}.${key} is not allowed`);
  }
}

function stringValue(
  value: unknown,
  path: string,
  errors: string[],
  bounds: { min?: number; max: number; pattern?: RegExp; trim?: boolean }
): string | undefined {
  if (typeof value !== 'string') {
    errors.push(`${path} must be a string`);
    return undefined;
  }
  const result = bounds.trim === false ? value : value.trim();
  if (result.length < (bounds.min ?? 0)) errors.push(`${path} must be at least ${bounds.min ?? 0} characters`);
  if (result.length > bounds.max) errors.push(`${path} must be at most ${bounds.max} characters`);
  if (bounds.pattern && !bounds.pattern.test(result)) errors.push(`${path} has an invalid format`);
  if (/^[\u0000-\u001f\u007f]/.test(result) || /[\u0000-\u001f\u007f]/.test(result)) {
    errors.push(`${path} contains a control character`);
  }
  return result;
}

const CONFIG_ID = /^[A-Za-z0-9._-]{1,64}$/;
const PRESET_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const HOST = /^[a-z0-9.-]+$/i;
const ALIAS = /^(?!\s)(?!.*\s$)[^\u0000-\u001f\u007f]+$/;

export function validConfigCollectionId(value: unknown): value is string {
  return typeof value === 'string' && CONFIG_ID.test(value);
}

export function validPresetId(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && PRESET_ID.test(value);
}

export function normalizeHost(value: unknown): string {
  if (typeof value !== 'string') throw new Error('host must be a string');
  let host = value.trim().toLowerCase();
  while (host.endsWith('.')) host = host.slice(0, -1);
  if (!host || host.length > 253 || !HOST.test(host)) throw new Error('host has an invalid format');
  return host;
}

export function normalizeFingerprint(value: unknown): string {
  if (typeof value !== 'string') throw new Error('fingerprint must be a string');
  const fingerprint = value.trim().replaceAll(':', '').toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(fingerprint)) throw new Error('fingerprint must be 64 hexadecimal characters');
  return fingerprint;
}

export function normalizeChannelPath(value: unknown): string {
  if (typeof value !== 'string') throw new Error('path must be a string');
  let path = value.trim();
  if (!path.startsWith('/')) throw new Error('path must start with /');
  path = path.replaceAll(/\/+/g, '/');
  if (path.length > 1) path = path.replace(/\/+$/, '');
  if (!path || path.length > 512 || /[\u0000-\u001f\u007f]/.test(path)) throw new Error('path has an invalid format');
  return path;
}

export function normalizePublicTokens(values: unknown): string[] {
  const source = Array.isArray(values) ? values : values === undefined ? [] : [values];
  const tokens = source.map((value, index) => {
    if (typeof value !== 'string') throw new Error(`tokens[${index}] must be a string`);
    const token = value.trim();
    if (!token || token.length > 1024 || /[\u0000-\u001f\u007f]/.test(token)) {
      throw new Error(`tokens[${index}] has an invalid format`);
    }
    return token;
  });
  return [...new Set(tokens)];
}

function normalizeConnection(value: unknown, path: string, errors: string[], idOverride?: string): ConfigPresetConnection | undefined {
  if (!isRecord(value)) {
    errors.push(`${path} must be an object`);
    return undefined;
  }
  rejectUnknown(value, CONNECTION_KEYS, path, errors);
  const rawId = idOverride ?? value.id;
  const id = stringValue(rawId, `${path}.id`, errors, { min: 1, max: 64, pattern: CONFIG_ID });
  let host: string | undefined;
  try { host = normalizeHost(value.host); } catch (error) { errors.push(`${path}.host ${String(error).replace(/^Error: /, '')}`); }
  const port = value.port;
  if (!Number.isInteger(port) || Number(port) < 1 || Number(port) > 65535) errors.push(`${path}.port must be an integer from 1 to 65535`);
  const result: ConfigPresetConnection = {
    id: id ?? '',
    host: host ?? '',
    port: Number(port)
  };
  if (has(value, 'name')) {
    const name = stringValue(value.name, `${path}.name`, errors, { min: 1, max: 128 });
    if (name !== undefined) result.name = name;
  }
  if (has(value, 'username')) {
    const username = stringValue(value.username, `${path}.username`, errors, { min: 1, max: 128 });
    if (username !== undefined) result.username = username;
  }
  if (has(value, 'password')) {
    const password = stringValue(value.password, `${path}.password`, errors, { max: 1024, trim: false });
    if (password !== undefined) result.password = password;
  }
  if (has(value, 'serverCertificateSha256')) {
    try { result.serverCertificateSha256 = normalizeFingerprint(value.serverCertificateSha256); }
    catch (error) { errors.push(`${path}.serverCertificateSha256 ${String(error).replace(/^Error: /, '')}`); }
  }
  if (has(value, 'autoTrustServerCertificate')) {
    if (typeof value.autoTrustServerCertificate !== 'boolean') errors.push(`${path}.autoTrustServerCertificate must be a boolean`);
    else result.autoTrustServerCertificate = value.autoTrustServerCertificate;
  }
  return result;
}

function normalizeAccess(value: unknown, path: string, errors: string[]): ConfigPresetAccess | undefined {
  if (!isRecord(value)) {
    errors.push(`${path} must be an object`);
    return undefined;
  }
  rejectUnknown(value, ACCESS_KEYS, path, errors);
  const mode = value.mode;
  if (mode !== 'none' && mode !== 'public' && mode !== 'protected') {
    errors.push(`${path}.mode must be none, public or protected`);
    return undefined;
  }
  const result: ConfigPresetAccess = { mode };
  const suppliedTokens: unknown[] = [];
  if (has(value, 'token')) suppliedTokens.push(value.token);
  if (has(value, 'tokens')) {
    if (!Array.isArray(value.tokens)) errors.push(`${path}.tokens must be an array`);
    else suppliedTokens.push(...value.tokens);
  }
  if (mode === 'public') {
    if (suppliedTokens.length > 0) {
      try {
        const tokens = normalizePublicTokens(suppliedTokens);
        if (tokens.length > 0) result.tokens = tokens;
      } catch (error) { errors.push(`${path} ${String(error).replace(/^Error: /, '')}`); }
    }
    if (has(value, 'tokenRef')) errors.push(`${path}.tokenRef is not allowed for public mode`);
  } else if (mode === 'protected') {
    if (suppliedTokens.length > 0) errors.push(`${path}.token/tokens are not allowed for protected mode`);
    if (has(value, 'tokenRef')) {
      const tokenRef = stringValue(value.tokenRef, `${path}.tokenRef`, errors, { min: 1, max: 128 });
      if (tokenRef !== undefined) result.tokenRef = tokenRef;
    }
  } else if (suppliedTokens.length > 0 || has(value, 'tokenRef')) {
    errors.push(`${path} secret fields are not allowed for none mode`);
  }
  return result;
}

function normalizeChannel(value: unknown, path: string, errors: string[], connectionId?: string): ConfigPresetChannel | undefined {
  if (!isRecord(value)) {
    errors.push(`${path} must be an object`);
    return undefined;
  }
  rejectUnknown(value, CHANNEL_KEYS, path, errors);
  const id = stringValue(value.id, `${path}.id`, errors, { min: 1, max: 64, pattern: CONFIG_ID });
  const label = stringValue(value.label, `${path}.label`, errors, { min: 1, max: 128 });
  const suppliedConnectionId = stringValue(value.connectionId, `${path}.connectionId`, errors, { min: 1, max: 64, pattern: CONFIG_ID });
  const resolvedConnectionId = connectionId ?? suppliedConnectionId;
  if (connectionId !== undefined && suppliedConnectionId !== undefined && suppliedConnectionId !== connectionId) {
    errors.push(`${path}.connectionId must reference ${connectionId}`);
  }
  let channelPath = '';
  try { channelPath = normalizeChannelPath(value.path); }
  catch (error) { errors.push(`${path}.path ${String(error).replace(/^Error: /, '')}`); }
  const access = normalizeAccess(value.access, `${path}.access`, errors);
  const result: ConfigPresetChannel = {
    id: id ?? '',
    label: label ?? '',
    connectionId: resolvedConnectionId ?? '',
    path: channelPath,
    access: access ?? { mode: 'none' }
  };
  if (has(value, 'alias')) {
    const alias = stringValue(value.alias, `${path}.alias`, errors, { min: 1, max: 32, pattern: ALIAS, trim: false });
    if (alias !== undefined) result.alias = alias;
  }
  if (has(value, 'presetKey')) {
    const presetKey = stringValue(value.presetKey, `${path}.presetKey`, errors, { min: 2, max: 3, pattern: /^P(?:[1-9]|1[0-6])$/ });
    if (presetKey !== undefined) result.presetKey = presetKey;
  }
  return result;
}

function normalizeOmissions(value: unknown, path: string, errors: string[]): ConfigPresetOmissionFlags | undefined {
  if (value === undefined) return undefined;
  if (!isRecord(value)) {
    errors.push(`${path} must be an object`);
    return undefined;
  }
  rejectUnknown(value, OMISSION_KEYS, path, errors);
  const result: ConfigPresetOmissionFlags = {};
  for (const key of OMISSION_KEYS) {
    if (has(value, key)) {
      if (typeof value[key] !== 'boolean') errors.push(`${path}.${key} must be a boolean`);
      else if (value[key] === true) result[key] = true;
    }
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

function normalizePreset(value: unknown): { preset?: StoredConfigPreset; errors: string[] } {
  const errors: string[] = [];
  if (!isRecord(value)) return { errors: ['$ must be an object'] };
  rejectUnknown(value, ['schemaVersion', 'id', 'name', 'connection', 'channels', 'omissions'], '$', errors);
  if (value.schemaVersion !== 1) errors.push('$.schemaVersion must be 1');
  const id = stringValue(value.id, '$.id', errors, { min: 1, max: 64, pattern: PRESET_ID });
  const name = stringValue(value.name, '$.name', errors, { min: 1, max: 128 });
  const connection = normalizeConnection(value.connection, '$.connection', errors);
  if (!Array.isArray(value.channels)) errors.push('$.channels must be an array');
  const channels: ConfigPresetChannel[] = [];
  if (Array.isArray(value.channels)) {
    if (value.channels.length < 1 || value.channels.length > 16) errors.push('$.channels must contain 1 to 16 channels');
    value.channels.forEach((raw, index) => {
      const channel = normalizeChannel(raw, `$.channels[${index}]`, errors, connection?.id);
      if (channel) channels.push(channel);
    });
  }
  const channelIds = new Set<string>();
  for (const [index, channel] of channels.entries()) {
    if (channelIds.has(channel.id)) errors.push(`$.channels[${index}].id must be unique`);
    channelIds.add(channel.id);
    if (connection && channel.connectionId !== connection.id) errors.push(`$.channels[${index}].connectionId must reference $.connection.id`);
  }
  const omissions = normalizeOmissions(value.omissions, '$.omissions', errors);
  if (errors.length > 0 || !connection || id === undefined || name === undefined) return { errors };
  const preset: StoredConfigPreset = {
    schemaVersion: 1,
    id,
    name,
    connection,
    channels
  };
  if (omissions) preset.omissions = omissions;
  const encodedLength = new TextEncoder().encode(JSON.stringify(preset)).length;
  if (encodedLength > 64 * 1024) errors.push('preset exceeds the 64 KiB limit');
  return errors.length > 0 ? { errors } : { preset, errors };
}

export function validateConfigPreset(value: unknown): ConfigPresetValidationResult {
  const result = normalizePreset(value);
  return { valid: result.errors.length === 0, errors: result.errors };
}

function inclusion(options?: ConfigPresetExportOptions | ConfigPresetSecretInclusion): ConfigPresetSecretInclusion {
  const source = (options || {}) as ConfigPresetExportOptions;
  return {
    username: source.username === true || source.include?.username === true,
    password: source.password === true || source.include?.password === true,
    fingerprint: source.fingerprint === true || source.include?.fingerprint === true,
    publicTokens: source.publicTokens === true || source.include?.publicTokens === true,
    protectedTokenRef: source.protectedTokenRef === true || source.include?.protectedTokenRef === true
  };
}

function hasAnyOmission(flags: ConfigPresetOmissionFlags) {
  return Object.values(flags).some(Boolean);
}

/**
 * Redact a preset for storage/export. Inclusion is opt-in for every credential,
 * including protected token references. A public token is never repurposed as a
 * protected token reference.
 */
export function redactConfigPreset(value: StoredConfigPreset, options?: ConfigPresetExportOptions): StoredConfigPreset {
  const normalized = normalizePreset(value);
  if (!normalized.preset) throw new Error(normalized.errors.join('; '));
  const preset = clonePresetValue(normalized.preset);
  const policy = inclusion(options);
  const omissions: ConfigPresetOmissionFlags = { ...(preset.omissions || {}) };
  const sourceConnection = preset.connection;
  if (sourceConnection.username !== undefined && !policy.username) {
    delete preset.connection.username;
    omissions.username = true;
  }
  if (sourceConnection.password !== undefined && !policy.password) {
    delete preset.connection.password;
    omissions.password = true;
  }
  if (sourceConnection.serverCertificateSha256 !== undefined && !policy.fingerprint) {
    delete preset.connection.serverCertificateSha256;
    omissions.fingerprint = true;
  }
  for (const channel of preset.channels) {
    const access = channel.access;
    if (access.mode === 'public') {
      if (access.tokens && access.tokens.length > 0 && !policy.publicTokens) {
        delete access.tokens;
        omissions.publicTokens = true;
      }
      delete access.token;
      delete access.tokenRef;
    } else if (access.mode === 'protected') {
      if (access.tokenRef !== undefined && !policy.protectedTokenRef) {
        delete access.tokenRef;
        omissions.protectedTokenRef = true;
      }
      delete access.token;
      delete access.tokens;
    } else {
      delete access.token;
      delete access.tokens;
      delete access.tokenRef;
    }
  }
  if (hasAnyOmission(omissions)) preset.omissions = omissions;
  else delete preset.omissions;
  return preset;
}

function presence(value: StoredConfigPreset): ConfigPresetSensitivePresence {
  return {
    username: value.connection.username !== undefined,
    password: value.connection.password !== undefined,
    fingerprint: value.connection.serverCertificateSha256 !== undefined,
    publicTokens: value.channels.some((channel) => channel.access.mode === 'public' && Boolean(channel.access.tokens?.length)),
    protectedTokenRef: value.channels.some((channel) => channel.access.mode === 'protected' && channel.access.tokenRef !== undefined)
  };
}

/** Safe list/detail representation. No secret value is copied into this object. */
export function safeConfigPresetRepresentation(value: StoredConfigPreset): SafeConfigPreset {
  const normalized = normalizePreset(value);
  if (!normalized.preset) throw new Error(normalized.errors.join('; '));
  const preset = normalized.preset;
  const flags = presence(preset);
  const result: SafeConfigPreset = {
    schemaVersion: 1,
    id: preset.id,
    name: preset.name,
    connection: {
      id: preset.connection.id,
      ...(preset.connection.name === undefined ? {} : { name: preset.connection.name }),
      host: preset.connection.host,
      port: preset.connection.port,
      ...(preset.connection.autoTrustServerCertificate === undefined ? {} : { autoTrustServerCertificate: preset.connection.autoTrustServerCertificate }),
      sensitive: { username: flags.username, password: flags.password, fingerprint: flags.fingerprint }
    },
    channels: preset.channels.map((channel) => ({
      id: channel.id,
      label: channel.label,
      ...(channel.alias === undefined ? {} : { alias: channel.alias }),
      connectionId: channel.connectionId,
      path: channel.path,
      ...(channel.presetKey === undefined ? {} : { presetKey: channel.presetKey }),
      access: {
        mode: channel.access.mode,
        sensitive: {
          publicTokens: channel.access.mode === 'public' && Boolean(channel.access.tokens?.length),
          protectedTokenRef: channel.access.mode === 'protected' && channel.access.tokenRef !== undefined
        }
      }
    }))
  };
  if (preset.omissions) result.omissions = clonePresetValue(preset.omissions);
  return result;
}

function knownConnection(value: unknown, id: string): AnyRecord {
  const source = isRecord(value) ? value : {};
  const result: AnyRecord = { id };
  for (const key of CONNECTION_KEYS) if (key !== 'id' && has(source, key)) result[key] = clonePresetValue(source[key]);
  return result;
}

function knownChannel(value: unknown): AnyRecord {
  const source = isRecord(value) ? value : {};
  const result: AnyRecord = {};
  for (const key of CHANNEL_KEYS) if (has(source, key)) result[key] = clonePresetValue(source[key]);
  return result;
}

function sourceConfig(value: MinimumConfig): MinimumConfigWithCollections {
  if (!isRecord(value)) throw new Error('source config must be an object');
  return value as MinimumConfigWithCollections;
}

function selectionOptions(selection: PresetSelection): ConfigPresetExportOptions {
  return {
    username: selection.username,
    password: selection.password,
    fingerprint: selection.fingerprint,
    publicTokens: selection.publicTokens,
    protectedTokenRef: selection.protectedTokenRef,
    include: selection.include
  };
}

/**
 * Copy only one connection and its selected channels out of a schema-3 config.
 * The returned preset is safe by default; credentials require explicit flags.
 */
export function createPresetFromSelection(
  config: MinimumConfig,
  selection: PresetSelection
): StoredConfigPreset {
  const source = sourceConfig(config);
  const connections = isRecord(source.connections) ? source.connections : {};
  const rawConnection = connections[selection.connectionId];
  if (!rawConnection) throw new Error(`connection ${selection.connectionId} was not found`);
  const rawChannels = Array.isArray(source.channels) ? source.channels : [];
  const wanted = selection.channelIds ? new Set(selection.channelIds) : undefined;
  const channels = rawChannels.filter((raw) => {
    if (!isRecord(raw) || raw.connectionId !== selection.connectionId) return false;
    return !wanted || wanted.has(typeof raw.id === 'string' ? raw.id : '');
  });
  if (channels.length < 1 || channels.length > 16) throw new Error('select between 1 and 16 channels for a preset');
  const rawPreset: StoredConfigPreset = {
    schemaVersion: 1,
    id: selection.id,
    name: selection.name,
    connection: knownConnection(rawConnection, selection.connectionId) as ConfigPresetConnection,
    channels: channels.map((raw) => knownChannel(raw) as ConfigPresetChannel)
  };
  const preset = redactConfigPreset(rawPreset, selectionOptions(selection));
  const validation = validateConfigPreset(preset);
  if (!validation.valid) throw new Error(validation.errors.join('; '));
  return preset;
}

export function normalizePresetForImport(value: unknown): StoredConfigPreset {
  const result = normalizePreset(value);
  if (!result.preset) throw new Error(result.errors.join('; '));
  return result.preset;
}

// Kept private to the import module while avoiding a second, subtly different normalizer.
export function normalizePresetConnectionForImport(value: unknown, id: string): ConfigPresetConnection {
  const errors: string[] = [];
  const result = normalizeConnection(value, '$.connection', errors, id);
  if (!result || errors.length > 0) throw new Error(errors.join('; '));
  return result;
}

export function normalizePresetChannelForImport(value: unknown, connectionId?: string): ConfigPresetChannel {
  const errors: string[] = [];
  const result = normalizeChannel(value, '$.channel', errors, connectionId);
  if (!result || errors.length > 0) throw new Error(errors.join('; '));
  return result;
}
