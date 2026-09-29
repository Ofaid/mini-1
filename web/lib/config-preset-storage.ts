import {
  clonePresetValue,
  normalizePresetForImport,
  redactConfigPreset,
  safeConfigPresetRepresentation,
  validateConfigPreset
} from './config-presets';
import type { SafeConfigPreset, StoredConfigPreset } from './config-library-types';
import { kvDelete, kvGet, kvList, kvPut } from './storage';

export const CONFIG_PRESET_PREFIX = 'config-preset:';
export const CONFIG_PRESET_MAX_COUNT = 100;
export const CONFIG_PRESET_MAX_BYTES = 64 * 1024;

export type ConfigPresetRecord = {
  preset: StoredConfigPreset;
  createdAt: string;
  updatedAt: string;
};

export type ConfigPresetSecretUpdateMode = 'preserve' | 'replace' | 'clear';
export type ConfigPresetChannelSecretUpdate = Partial<Record<'publicTokens' | 'protectedTokenRef', ConfigPresetSecretUpdateMode>>;
/** Connection policies remain flat for compatibility; channel policies are keyed by channel ID. */
export type ConfigPresetSecretUpdate = Partial<Record<'username' | 'password' | 'fingerprint' | 'publicTokens' | 'protectedTokenRef', ConfigPresetSecretUpdateMode>> & {
  channels?: Record<string, ConfigPresetChannelSecretUpdate>;
};

export type SafeConfigPresetRecord = SafeConfigPreset & {
  createdAt: string;
  updatedAt: string;
};

export class ConfigPresetStoreError extends Error {
  readonly code: 'not-found' | 'conflict' | 'capacity' | 'invalid' | 'unavailable';

  constructor(code: ConfigPresetStoreError['code'], message: string) {
    super(message);
    this.name = 'ConfigPresetStoreError';
    this.code = code;
  }
}

function presetKey(id: string) {
  return `${CONFIG_PRESET_PREFIX}${id}`;
}

function byteLength(value: unknown) {
  return new TextEncoder().encode(JSON.stringify(value)).length;
}

function assertPreset(value: unknown): StoredConfigPreset {
  const result = validateConfigPreset(value);
  if (!result.valid) throw new ConfigPresetStoreError('invalid', result.errors.join('; '));
  try {
    return normalizePresetForImport(value);
  } catch (error) {
    throw new ConfigPresetStoreError('invalid', error instanceof Error ? error.message : 'Invalid preset');
  }
}

function assertSize(value: StoredConfigPreset) {
  if (byteLength(value) > CONFIG_PRESET_MAX_BYTES) {
    throw new ConfigPresetStoreError('invalid', 'Preset exceeds the 64 KiB limit');
  }
}

function assertRecord(value: unknown, id: string): ConfigPresetRecord | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const source = value as Record<string, unknown>;
  if (typeof source.createdAt !== 'string' || typeof source.updatedAt !== 'string' || !source.preset) return null;
  try {
    const preset = assertPreset(source.preset);
    if (preset.id !== id) return null;
    return { preset, createdAt: source.createdAt, updatedAt: source.updatedAt };
  } catch {
    return null;
  }
}

export async function getConfigPreset(id: string): Promise<ConfigPresetRecord | null> {
  const value = await kvGet<unknown>(presetKey(id));
  return assertRecord(value, id);
}

export async function listConfigPresets(query?: string): Promise<ConfigPresetRecord[]> {
  const keys = await kvList(CONFIG_PRESET_PREFIX);
  const records = await Promise.all(keys.slice(0, CONFIG_PRESET_MAX_COUNT).map(async (key) => {
    const id = key.slice(CONFIG_PRESET_PREFIX.length);
    return getConfigPreset(id);
  }));
  const needle = query?.trim().toLocaleLowerCase();
  return records
    .filter((record): record is ConfigPresetRecord => Boolean(record))
    .filter((record) => !needle || record.preset.id.toLocaleLowerCase().includes(needle)
      || record.preset.name.toLocaleLowerCase().includes(needle)
      || (record.preset.connection.name || '').toLocaleLowerCase().includes(needle)
      || record.preset.connection.host.toLocaleLowerCase().includes(needle)
      || record.preset.channels.some((channel) => [channel.label, channel.alias || '', channel.path, channel.id].some((field) => field.toLocaleLowerCase().includes(needle))))
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || left.preset.id.localeCompare(right.preset.id));
}

export function safeConfigPresetRecord(record: ConfigPresetRecord): SafeConfigPresetRecord {
  return {
    ...safeConfigPresetRepresentation(record.preset),
    createdAt: record.createdAt,
    updatedAt: record.updatedAt
  };
}

export async function createConfigPreset(presetValue: unknown, now = new Date().toISOString()): Promise<ConfigPresetRecord> {
  const preset = assertPreset(presetValue);
  assertSize(preset);
  const existing = await getConfigPreset(preset.id);
  if (existing) throw new ConfigPresetStoreError('conflict', 'Preset already exists');
  const keys = await kvList(CONFIG_PRESET_PREFIX);
  if (keys.length >= CONFIG_PRESET_MAX_COUNT) throw new ConfigPresetStoreError('capacity', 'Preset capacity reached');
  const record: ConfigPresetRecord = { preset: clonePresetValue(preset), createdAt: now, updatedAt: now };
  await kvPut(presetKey(preset.id), record);
  return record;
}

export async function updateConfigPreset(
  id: string,
  presetValue: unknown,
  expectedUpdatedAt?: string,
  now = new Date().toISOString(),
  secretUpdates: ConfigPresetSecretUpdate = {}
): Promise<ConfigPresetRecord> {
  const current = await getConfigPreset(id);
  if (!current) throw new ConfigPresetStoreError('not-found', 'Preset not found');
  if (expectedUpdatedAt !== undefined && expectedUpdatedAt !== current.updatedAt) {
    throw new ConfigPresetStoreError('conflict', 'Preset was updated by another request');
  }
  const preset = assertPreset(presetValue);
  if (preset.id !== id) throw new ConfigPresetStoreError('invalid', 'Preset ID does not match the route');
  const currentPreset = current.preset;
  const effective = clonePresetValue(preset);
  const connectionSecretKeys: Array<['username' | 'password' | 'fingerprint', 'username' | 'password' | 'serverCertificateSha256']> = [
    ['username', 'username'], ['password', 'password'], ['fingerprint', 'serverCertificateSha256']
  ];
  for (const [policyKey, valueKey] of connectionSecretKeys) {
    const mode = secretUpdates[policyKey] || (effective.connection[valueKey] === undefined ? 'preserve' : 'replace');
    if (mode === 'preserve' && currentPreset.connection[valueKey] !== undefined) effective.connection[valueKey] = currentPreset.connection[valueKey];
    if (mode === 'clear') delete effective.connection[valueKey];
    if (mode === 'replace' && effective.connection[valueKey] === undefined && currentPreset.connection[valueKey] !== undefined) {
      throw new ConfigPresetStoreError('invalid', `${policyKey} replacement value is required`);
    }
  }
  const currentChannels = new Map(currentPreset.channels.map((channel) => [channel.id, channel]));
  for (const channel of effective.channels) {
    const currentChannel = currentChannels.get(channel.id);
    if (!currentChannel) continue;
    const publicMode = secretUpdates.channels?.[channel.id]?.publicTokens
      || secretUpdates.publicTokens
      || (channel.access.mode === 'public' && channel.access.tokens === undefined ? 'preserve' : 'replace');
    if (channel.access.mode === 'public') {
      if (publicMode === 'preserve' && currentChannel.access.mode === 'public' && currentChannel.access.tokens !== undefined) channel.access.tokens = clonePresetValue(currentChannel.access.tokens);
      if (publicMode === 'clear') delete channel.access.tokens;
      if (publicMode === 'replace' && channel.access.tokens === undefined && currentChannel.access.tokens !== undefined) throw new ConfigPresetStoreError('invalid', 'publicTokens replacement value is required');
    }
    const protectedMode = secretUpdates.channels?.[channel.id]?.protectedTokenRef
      || secretUpdates.protectedTokenRef
      || (channel.access.mode === 'protected' && channel.access.tokenRef === undefined ? 'preserve' : 'replace');
    if (channel.access.mode === 'protected') {
      if (protectedMode === 'preserve' && currentChannel.access.mode === 'protected' && currentChannel.access.tokenRef !== undefined) channel.access.tokenRef = currentChannel.access.tokenRef;
      if (protectedMode === 'clear') delete channel.access.tokenRef;
      if (protectedMode === 'replace' && channel.access.tokenRef === undefined && currentChannel.access.tokenRef !== undefined) throw new ConfigPresetStoreError('invalid', 'protectedTokenRef replacement value is required');
    }
  }
  const normalizedEffective = assertPreset(effective);
  assertSize(normalizedEffective);
  const record: ConfigPresetRecord = { preset: clonePresetValue(normalizedEffective), createdAt: current.createdAt, updatedAt: now };
  await kvPut(presetKey(id), record);
  return record;
}

export async function deleteConfigPreset(id: string, expectedUpdatedAt?: string): Promise<void> {
  const current = await getConfigPreset(id);
  if (!current) throw new ConfigPresetStoreError('not-found', 'Preset not found');
  if (expectedUpdatedAt !== undefined && expectedUpdatedAt !== current.updatedAt) {
    throw new ConfigPresetStoreError('conflict', 'Preset was updated by another request');
  }
  await kvDelete(presetKey(id));
}

export async function duplicateConfigPreset(
  sourceId: string,
  duplicateValue: unknown,
  now = new Date().toISOString()
): Promise<ConfigPresetRecord> {
  const source = await getConfigPreset(sourceId);
  if (!source) throw new ConfigPresetStoreError('not-found', 'Preset not found');
  return createConfigPreset(duplicateValue, now);
}

/** Return a secret-filtered clone for an import operation. */
export function releaseConfigPresetForImport(record: ConfigPresetRecord, options: {
  username?: boolean;
  password?: boolean;
  fingerprint?: boolean;
  publicTokens?: boolean;
  protectedTokenRef?: boolean;
} = {}): StoredConfigPreset {
  return redactConfigPreset(record.preset, options);
}
