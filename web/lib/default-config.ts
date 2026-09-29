import type { MinimumConfig } from './types';
import type { ModelProfile } from './model-profiles';

export type ConfigObject = Record<string, unknown>;

type HardwareProfile = ConfigObject & {
  profile: string;
  pttKeyCode: number;
  pttScanCode: number;
  pttKeyCodes: number[];
  pttScanCodes?: number[];
  locationTrackingSupported: boolean;
  p1KeyCode: number;
  p2KeyCode: number;
  p3KeyCode: number;
  p1ScanCode?: number;
  p2ScanCode?: number;
  p3ScanCode?: number;
  greenKeyCode?: number;
  greenScanCode?: number;
  menuKeyCode?: number;
  menuScanCode?: number;
  redKeyCode?: number;
  redScanCode?: number;
};

export const HARDWARE_BY_MODEL: Record<ModelProfile, HardwareProfile> = {
  ryks: {
    profile: 'ryks-elink-ym-258',
    pttKeyCode: 285,
    pttScanCode: 216,
    pttKeyCodes: [285, 85, 79],
    pttScanCodes: [216, 249],
    locationTrackingSupported: false,
    p1KeyCode: 138,
    p1ScanCode: 66,
    p2KeyCode: 137,
    p2ScanCode: 65,
    p3KeyCode: 0,
    greenKeyCode: 23,
    greenScanCode: 353,
    menuKeyCode: 132,
    menuScanCode: 60,
    redKeyCode: 26,
    redScanCode: 116
  },
  t56: {
    profile: 't56-unipro-zx-l809',
    pttKeyCode: 261,
    pttScanCode: 216,
    pttKeyCodes: [261, 85, 79],
    locationTrackingSupported: true,
    p1KeyCode: 0,
    p2KeyCode: 0,
    p3KeyCode: 0
  },
  t99: {
    profile: 't99-qm011',
    pttKeyCode: 0,
    pttScanCode: 0,
    pttKeyCodes: [131, 132, 85, 79],
    locationTrackingSupported: false,
    p1KeyCode: 0,
    p2KeyCode: 0,
    p3KeyCode: 0
  },
  'generic-radio': {
    profile: 'generic-radio',
    pttKeyCode: 0,
    pttScanCode: 0,
    pttKeyCodes: [],
    locationTrackingSupported: false,
    p1KeyCode: 0,
    p2KeyCode: 0,
    p3KeyCode: 0
  }
};

export const SERVICE_NAME_BY_MODEL: Record<ModelProfile, string> = {
  ryks: 'Minimum RYKS',
  t56: 'Minimum T56',
  t99: 'Minimum T99',
  'generic-radio': 'Minimum Radio'
};

// The bundled Android baseline is config v6; portal-issued configs must not look like downgrades.
export const INITIAL_PORTAL_CONFIG_VERSION = 7;

/**
 * Return a JSON-safe copy so callers can edit a config without mutating the template constants.
 */
export function cloneConfigValue<T>(value: T): T {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
}

/**
 * The portal baseline mirrors backend/default.json and is intentionally non-credentialed.
 * Model files own only modelProfile, service.name and hardware capability/key values.
 */
export function emptyConfig(deviceId: string, model: ModelProfile = 'generic-radio'): MinimumConfig {
  return cloneConfigValue({
    schemaVersion: 3,
    configVersion: INITIAL_PORTAL_CONFIG_VERSION,
    deviceId,
    modelProfile: model,
    service: { name: SERVICE_NAME_BY_MODEL[model] },
    radio: { defaultChannel: 'main', autoConnect: false, autoReconnect: true },
    connections: {
      'public-main': {
        name: 'Minimum Public PTT',
        host: 'voice.example.invalid',
        port: 64738,
        username: 'MINIMUM',
        autoTrustServerCertificate: true
      }
    },
    channels: [{
      id: 'main',
      label: 'Main room',
      alias: 'MAIN',
      connectionId: 'public-main',
      path: '/PUBLIC/MAIN',
      presetKey: 'P1',
      access: { mode: 'none' }
    }],
    ui: {
      profile: 'small-radio',
      language: 'th',
      showChat: false,
      showUserList: false,
      showChannelTree: false,
      allowExit: false,
      voicePrompt: true
    },
    ptt: { maximumTxSeconds: 120, allowScreenOff: true, releaseOnNetworkLoss: true },
    // New profiles never opt into location or APRS transmission by default.
    tracking: {
      enabled: false,
      pttTriggered: true,
      aprs: { enabled: false, host: 'ametx.com', port: 8888 }
    },
    hardware: HARDWARE_BY_MODEL[model],
    update: { checkOnBoot: true, checkIntervalMinutes: 360, applyMode: 'when-idle' }
  } as MinimumConfig);
}

/** Apply only model-owned values while preserving connections, channels and private settings. */
export function applyModelProfile(value: MinimumConfig, model: ModelProfile): MinimumConfig {
  const source = cloneConfigValue(value) as MinimumConfig & {
    service?: ConfigObject;
    hardware?: ConfigObject;
    tracking?: ConfigObject & { aprs?: ConfigObject };
  };
  source.modelProfile = model;
  source.service = { ...(source.service || {}), name: SERVICE_NAME_BY_MODEL[model] };
  source.hardware = { ...(source.hardware || {}), ...cloneConfigValue(HARDWARE_BY_MODEL[model]) };
  const aprs = { ...(source.tracking?.aprs || {}) };
  source.tracking = { ...(source.tracking || {}), aprs };
  if (!HARDWARE_BY_MODEL[model].locationTrackingSupported) {
    source.tracking.enabled = false;
    aprs.enabled = false;
  }
  return source;
}

export const changeModel = applyModelProfile;

/** Stable alias for callers that want to make the intent explicit. */
export const createConfigTemplate = emptyConfig;
