export type MinimumConfig = {
  schemaVersion: number;
  configVersion: number;
  deviceId: string;
  [key: string]: unknown;
};

export type StoredAdmin = {
  username: string;
  passwordHash: string;
  createdAt: string;
  updatedAt: string;
};

export type StoredDevice = {
  deviceId: string;
  label: string;
  model: ModelProfile;
  config: MinimumConfig;
  createdAt: string;
  updatedAt: string;
};

export type DeviceSummary = Omit<StoredDevice, 'config'> & {
  configVersion: number;
};

export type PendingDeviceRequest = {
  deviceId: string;
  firstSeenAt: string;
  lastSeenAt: string;
  requestCount: number;
};

export type PendingDeviceRequestSummary = PendingDeviceRequest;

export type DismissedPendingDevice = {
  deviceId: string;
  dismissedAt: string;
  expiresAt: string;
};

/**
 * Operational configuration-delivery counters. These values are intentionally
 * best effort and describe HTTP configuration responses only; they do not
 * indicate that a client parsed, activated, or connected with a profile.
 */
export type DeviceDeliveryStats = {
  deviceId: string;
  profileCreatedAt: string;
  firstRequestAt?: string;
  lastRequestAt?: string;
  lastServedAt?: string;
  lastConfigVersionServed?: number;
  requestCount: number;
  servedCount: number;
};

export type ActivityCategory = 'administrator' | 'device-configuration' | 'system';
export type ActivityActorType = 'administrator' | 'device' | 'system';
export type ActivityResult =
  | 'succeeded'
  | 'served'
  | 'not-found'
  | 'invalid'
  | 'failed';

export type ActivityAction =
  | 'admin.login.succeeded'
  | 'admin.logout'
  | 'device.created'
  | 'device.updated'
  | 'device.deleted'
  | 'pending-request.dismissed'
  | 'preset.created'
  | 'preset.updated'
  | 'preset.deleted'
  | 'config.request.succeeded'
  | 'config.request.unknown-device'
  | 'config.request.invalid-device-id'
  | 'config.request.failed'
  | 'storage.unavailable'
  | 'configuration.validation.failed'
  | 'activity-log.write.failed';

export type SafeConfigChangeSection =
  | 'label'
  | 'model'
  | 'connections'
  | 'channels'
  | 'radio'
  | 'audio'
  | 'tracking'
  | 'management';

export type SafeConfigChangeSummary = {
  sections: SafeConfigChangeSection[];
  connectionsBefore?: number;
  connectionsAfter?: number;
  channelsBefore?: number;
  channelsAfter?: number;
};

export type ActivityActor = {
  type: ActivityActorType;
  username?: string;
};

export type ActivityResource = {
  type: 'device' | 'pending-device' | 'preset' | 'system';
  id?: string;
  label?: string;
  model?: string;
};

/** Safe, allowlisted operational activity record. */
export type ActivityEvent = {
  schemaVersion: 1;
  id: string;
  occurredAt: string;
  category: ActivityCategory;
  action: ActivityAction;
  actor: ActivityActor;
  resource: ActivityResource;
  result: ActivityResult;
  configVersions?: {
    previous?: number;
    current?: number;
    served?: number;
  };
  change?: SafeConfigChangeSummary;
  correlationId: string;
};
import type { ModelProfile } from './model-profiles';
