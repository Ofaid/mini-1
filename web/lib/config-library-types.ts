import type { MinimumConfig } from './types';

/** The fields from a schema-3 connection that may be reused by a preset. */
export type ConfigPresetConnection = {
  id: string;
  name?: string;
  host: string;
  port: number;
  username?: string;
  password?: string;
  serverCertificateSha256?: string;
  autoTrustServerCertificate?: boolean;
};

export type ConfigPresetAccess = {
  mode: 'none' | 'public' | 'protected';
  /** Canonical public-token representation. A redacted preset may omit it. */
  tokens?: string[];
  /** Accepted on input and source bundles; canonical presets use `tokens`. */
  token?: string;
  /** A protected reference is retained only when explicitly included. */
  tokenRef?: string;
};

export type ConfigPresetChannel = {
  id: string;
  label: string;
  alias?: string;
  connectionId: string;
  path: string;
  presetKey?: string;
  access: ConfigPresetAccess;
};

/** Fields intentionally omitted by the default (safe) export. */
export type ConfigPresetOmissionFlags = {
  username?: boolean;
  password?: boolean;
  fingerprint?: boolean;
  publicTokens?: boolean;
  protectedTokenRef?: boolean;
};

/** A safe, one-server reusable preset. It never contains device-owned fields. */
export type StoredConfigPreset = {
  schemaVersion: 1;
  id: string;
  name: string;
  connection: ConfigPresetConnection;
  channels: ConfigPresetChannel[];
  omissions?: ConfigPresetOmissionFlags;
};

export type ConfigPresetSecretInclusion = {
  username?: boolean;
  password?: boolean;
  fingerprint?: boolean;
  publicTokens?: boolean;
  protectedTokenRef?: boolean;
};

export type ConfigPresetExportOptions = ConfigPresetSecretInclusion & {
  /** Optional nested form for callers that keep export policy together. */
  include?: ConfigPresetSecretInclusion;
};

export type ConfigPresetSensitivePresence = {
  username: boolean;
  password: boolean;
  fingerprint: boolean;
  publicTokens: boolean;
  protectedTokenRef: boolean;
};

export type SafeConfigPresetConnection = Omit<ConfigPresetConnection, 'username' | 'password' | 'serverCertificateSha256'> & {
  sensitive: Pick<ConfigPresetSensitivePresence, 'username' | 'password' | 'fingerprint'>;
};

export type SafeConfigPresetAccess = {
  mode: ConfigPresetAccess['mode'];
  sensitive: Pick<ConfigPresetSensitivePresence, 'publicTokens' | 'protectedTokenRef'>;
};

export type SafeConfigPreset = Omit<StoredConfigPreset, 'connection' | 'channels'> & {
  connection: SafeConfigPresetConnection;
  channels: Array<Omit<ConfigPresetChannel, 'access'> & { access: SafeConfigPresetAccess }>;
};

export type ConfigPresetValidationResult = {
  valid: boolean;
  errors: string[];
};

export type PresetSelection = {
  id: string;
  name: string;
  connectionId: string;
  channelIds?: string[];
  includeDefaultChannel?: boolean;
  include?: ConfigPresetSecretInclusion;
} & ConfigPresetSecretInclusion;

/** Deliberately narrow source bundle: no model, device, service or radio policy. */
export type ConfigImportBundle = {
  schemaVersion: 1;
  connection: ConfigPresetConnection;
  channels: ConfigPresetChannel[];
  /** Present only when the caller explicitly selected the source default channel. */
  defaultChannelId?: string;
};

export type SafeConfigImportBundle = {
  schemaVersion: 1;
  connection: SafeConfigPresetConnection;
  channels: Array<Omit<ConfigPresetChannel, 'access'> & { access: SafeConfigPresetAccess }>;
  defaultChannelId?: string;
};

export type ImportDuplicateConnectionAction = 'reuse' | 'add';
export type ImportDuplicateChannelAction = 'skip' | 'add' | 'replace';
export type ImportPresetKeyAction = 'unassign' | 'unused' | 'replace';

export type ConfigImportOptions = {
  /** Explicitly resolve a same-endpoint connection with a different ID. */
  connectionDuplicate?: ImportDuplicateConnectionAction;
  /** Explicitly resolve a duplicate resolved-connection/path channel. */
  channelDuplicate?: ImportDuplicateChannelAction;
  /** How an imported P1..P16 key colliding with a target key is handled. */
  presetKeyConflict?: ImportPresetKeyAction;
  /** Import the bundle's explicitly selected source default channel. */
  importDefaultChannel?: boolean;
};

export type ImportDecisionKind =
  | 'duplicate-connection'
  | 'pinned-fingerprint'
  | 'duplicate-channel'
  | 'preset-key-conflict';

export type ImportDecision = {
  kind: ImportDecisionKind;
  sourceId?: string;
  targetId?: string;
  detail: string;
  choices: string[];
};

export type ConfigImportPreview = {
  canApply: boolean;
  blocked: boolean;
  errors: string[];
  warnings: string[];
  decisions: ImportDecision[];
  source: SafeConfigImportBundle;
  connectionIdMap: Record<string, string>;
  channelIdMap: Record<string, string>;
  /** Counts after applying the selected decisions, before the actual draft. */
  resultingCounts: { connections: number; channels: number };
};

export type MinimumConfigWithCollections = MinimumConfig & {
  connections?: Record<string, unknown>;
  channels?: unknown[];
  radio?: Record<string, unknown>;
};
