import Ajv2020 from 'ajv/dist/2020';
import schema from './config-schema.json';
import {
  cloneConfigValue,
  emptyConfig,
  applyModelProfile,
  HARDWARE_BY_MODEL,
  INITIAL_PORTAL_CONFIG_VERSION,
  type ConfigObject
} from './default-config';
import type { ModelProfile } from './model-profiles';
import { validModelProfile } from './model-profiles';
import type { MinimumConfig } from './types';
import { validDeviceId } from './security';
import { calculateAprsPasscode, normalizeAprsCallsign } from './aprs';
export { applyModelProfile, changeModel, emptyConfig, createConfigTemplate } from './default-config';

const validator = new Ajv2020({ allErrors: true, strict: false });
const validateSchema = validator.compile(schema);

function isRecord(value: unknown): value is ConfigObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function cloneRecord(value: ConfigObject): ConfigObject {
  return cloneConfigValue(value);
}

/** Deep merge objects while replacing arrays/scalars with the supplied value. */
function mergeConfigObjects(base: ConfigObject, overlay: ConfigObject): ConfigObject {
  const result = cloneRecord(base);
  for (const [key, value] of Object.entries(overlay)) {
    if (isRecord(value) && isRecord(result[key])) {
      result[key] = mergeConfigObjects(result[key] as ConfigObject, value);
    } else {
      result[key] = cloneConfigValue(value);
    }
  }
  return result;
}

function repairChannelDefaults(base: ConfigObject, source: ConfigObject): unknown[] {
  const channels = source.channels;
  const baseChannels = Array.isArray(base.channels) ? base.channels : [];
  if (!Array.isArray(channels) || channels.length === 0) return cloneConfigValue(baseChannels);
  return channels.map((rawChannel, index) => {
    if (!isRecord(rawChannel)) return rawChannel;
    const fallback = index === 0 && isRecord(baseChannels[0])
      ? baseChannels[0]
      : { access: { mode: 'none' } };
    const channel = mergeConfigObjects(fallback as ConfigObject, rawChannel);
    const id = typeof channel.id === 'string' && channel.id.length > 0
      ? channel.id
      : index === 0 ? 'main' : '';
    if (!channel.alias && id) channel.alias = id.slice(0, 32);
    if (!channel.presetKey && index < 16) channel.presetKey = `P${index + 1}`;
    if (!isRecord(channel.access)) channel.access = { mode: 'none' };
    return channel;
  });
}

function modelFromConfig(value: ConfigObject): ModelProfile {
  if (validModelProfile(value.modelProfile)) return value.modelProfile;
  const hardwareProfile = isRecord(value.hardware) ? value.hardware.profile : undefined;
  if (typeof hardwareProfile === 'string') {
    const inferred = (Object.keys(HARDWARE_BY_MODEL) as ModelProfile[])
      .find((model) => HARDWARE_BY_MODEL[model].profile === hardwareProfile);
    if (inferred) return inferred;
  }
  return 'generic-radio';
}

function deviceIdFromConfig(value: ConfigObject, deviceId?: string): string {
  return deviceId ?? (typeof value.deviceId === 'string' ? value.deviceId : '*');
}

function positiveConfigVersion(value: unknown): number {
  return Number.isInteger(value)
    ? Math.max(Number(value), INITIAL_PORTAL_CONFIG_VERSION)
    : INITIAL_PORTAL_CONFIG_VERSION;
}

function withoutConfigVersion(value: unknown): unknown {
  if (!isRecord(value)) return value;
  const copy = cloneRecord(value);
  delete copy.configVersion;
  return copy;
}

export function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as ConfigObject)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, nested]) => [key, canonicalize(nested)]));
  }
  return value;
}

export function configsEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(canonicalize(left)) === JSON.stringify(canonicalize(right));
}

/** Compare effective content without treating the monotonically increasing version as content. */
export function effectiveConfigsEqual(left: unknown, right: unknown): boolean {
  return configsEqual(withoutConfigVersion(left), withoutConfigVersion(right));
}

function validateChannelAccess(access: unknown, index: number, errors: string[]) {
  if (!isRecord(access)) return;
  const mode = access.mode;
  if (mode === 'public') {
    const hasToken = typeof access.token === 'string' && access.token.length > 0;
    const hasTokens = Array.isArray(access.tokens) && access.tokens.length > 0;
    if (!hasToken && !hasTokens) {
      errors.push(`$.channels[${index}].access requires token or tokens for public mode`);
    }
  } else if (mode === 'protected') {
    if (typeof access.tokenRef !== 'string' || access.tokenRef.length === 0) {
      errors.push(`$.channels[${index}].access requires tokenRef for protected mode`);
    }
  }
}

export function validateConfig(value: unknown, deviceId?: string) {
  const errors: string[] = [];
  if (!validateSchema(value)) {
    for (const error of validateSchema.errors || []) {
      errors.push(`${error.instancePath || '$'} ${error.message || 'is invalid'}`);
    }
  }

  const config = isRecord(value) ? value : null;
  if (!config) errors.push('$ must be an object');
  if (config && config.schemaVersion !== 3) errors.push('$.schemaVersion must be 3');
  if (config && (!Number.isInteger(config.configVersion) || Number(config.configVersion) < 1)) {
    errors.push('$.configVersion must be a positive integer');
  }
  if (deviceId && config && config.deviceId !== '*' && config.deviceId !== deviceId) {
    errors.push(`$.deviceId must be ${deviceId} or *`);
  }

  if (config) {
    const hardware = isRecord(config.hardware) ? config.hardware : null;
    const tracking = isRecord(config.tracking) ? config.tracking : null;
    if (tracking?.enabled === true && hardware?.locationTrackingSupported !== true) {
      errors.push('$.tracking.enabled requires hardware.locationTrackingSupported=true');
    }
  }

  if (config && Array.isArray(config.channels)
      && isRecord(config.connections)) {
    const channels = config.channels as unknown[];
    const connections = config.connections;
    const channelIds = new Set<string>();
    for (const [index, rawChannel] of channels.entries()) {
      const channel = isRecord(rawChannel) ? rawChannel : null;
      const id = typeof channel?.id === 'string' ? channel.id : '';
      if (id && channelIds.has(id)) errors.push(`$.channels[${index}].id must be unique`);
      if (id) channelIds.add(id);
      const connectionId = typeof channel?.connectionId === 'string' ? channel.connectionId : '';
      if (connectionId && !Object.prototype.hasOwnProperty.call(connections, connectionId)) {
        errors.push(`$.channels[${index}].connectionId must reference an existing connection`);
      }
      validateChannelAccess(channel?.access, index, errors);
    }
    const radio = isRecord(config.radio) ? config.radio : null;
    const defaultChannel = radio?.defaultChannel;
    if (typeof defaultChannel === 'string' && !channelIds.has(defaultChannel)) {
      errors.push('$.radio.defaultChannel must reference an existing channel');
    }
  }
  return { valid: errors.length === 0, errors };
}

/**
 * Fill missing schema-3 sections from the canonical baseline without discarding supplied values.
 * A model overlay is then applied so the resulting service/hardware capability is coherent.
 */
export function repairConfig(value: unknown, deviceId?: string, model?: ModelProfile): MinimumConfig {
  const source = isRecord(value) ? value : {};
  const resolvedModel = model ?? modelFromConfig(source);
  const resolvedDeviceId = deviceIdFromConfig(source, deviceId);
  const template = emptyConfig(resolvedDeviceId, resolvedModel) as ConfigObject;
  const merged = mergeConfigObjects(template, source) as MinimumConfig;
  // Connections are an ID-keyed collection, not a partial object overlay. Once a
  // config supplies the collection, preserve it exactly so the portal baseline
  // cannot reintroduce its placeholder public-main connection.
  if (isRecord(source.connections) && Object.keys(source.connections).length > 0) {
    merged.connections = cloneRecord(source.connections);
  }
  merged.channels = repairChannelDefaults(template, source);
  const channels = Array.isArray(merged.channels)
    ? merged.channels.filter(isRecord)
    : [];
  const channelIds = new Set(channels
    .map((channel) => typeof channel.id === 'string' ? channel.id : '')
    .filter(Boolean));
  const radio = isRecord(merged.radio) ? merged.radio : {};
  if (typeof radio.defaultChannel !== 'string' || !channelIds.has(radio.defaultChannel)) {
    const firstChannelId = channels.find((channel) => typeof channel.id === 'string')?.id;
    if (firstChannelId) radio.defaultChannel = firstChannelId;
  }
  merged.radio = radio;
  const tracking = isRecord(merged.tracking) ? merged.tracking : {};
  const aprs = isRecord(tracking.aprs) ? tracking.aprs : {};
  if (typeof aprs.sourceCallsign === 'string') {
    const sourceCallsign = normalizeAprsCallsign(aprs.sourceCallsign);
    const passcode = calculateAprsPasscode(sourceCallsign);
    aprs.sourceCallsign = sourceCallsign;
    if (passcode) aprs.passcode = passcode;
  }
  tracking.aprs = aprs;
  merged.tracking = tracking;
  merged.schemaVersion = 3;
  merged.deviceId = resolvedDeviceId;
  merged.configVersion = positiveConfigVersion(source.configVersion);
  return applyModelProfile(merged, resolvedModel);
}

/**
 * Normalize a draft before persistence. Effective changes advance from the previous version;
 * unchanged content retains the previous version even when a UI draft contains a stale/manual one.
 */
export function prepareConfigForSave(
  value: unknown,
  previous?: MinimumConfig,
  deviceId?: string
): MinimumConfig {
  const source = isRecord(value) ? value : {};
  const model = modelFromConfig(source);
  const prepared = repairConfig(source, deviceId, model);
  if (!previous) return prepared;

  // Compare against the stored shape, not a repaired copy: filling a missing field is an
  // effective persisted change and must advance the version accepted by the API.
  const baselineVersion = positiveConfigVersion(previous.configVersion);
  if (effectiveConfigsEqual(prepared, previous)) {
    return { ...prepared, configVersion: baselineVersion };
  }
  if (baselineVersion >= Number.MAX_SAFE_INTEGER) {
    throw new Error('config version cannot be advanced');
  }
  // Imported physical configs may already be ahead of the portal record; never turn them
  // into an Android-visible downgrade while still advancing ordinary UI edits automatically.
  return {
    ...prepared,
    configVersion: Math.max(baselineVersion + 1, positiveConfigVersion(source.configVersion))
  };
}

export function assertDeviceConfig(value: unknown, deviceId: string) {
  if (!validDeviceId(deviceId)) throw new Error('Invalid device ID');
  const result = validateConfig(value, deviceId);
  if (!result.valid) throw new Error(result.errors.join('; '));
  return value as MinimumConfig;
}
