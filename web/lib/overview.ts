import type {
  ActivityEvent,
  DeviceDeliveryStats,
  PendingDeviceRequest,
  StoredDevice
} from './types';

export const HOUR_MS = 60 * 60 * 1000;
export const DAY_MS = 24 * HOUR_MS;
export const DEFAULT_STALE_WINDOW_MS = 7 * DAY_MS;

export type OverviewStorageHealth = {
  ok: boolean;
  backend: 'cloudflare' | 'memory';
  latencyMs: number;
};

export type DeviceFetchStatus = 'current' | 'behind' | 'stale' | 'never-fetched';

export type OverviewDevice = {
  deviceId: string;
  label: string;
  model: StoredDevice['model'];
  createdAt: string;
  updatedAt: string;
  currentVersion: number;
  lastServedVersion?: number;
  firstRequestAt?: string;
  lastRequestAt?: string;
  lastServedAt?: string;
  requestCount: number;
  servedCount: number;
  status: DeviceFetchStatus;
};

export type OverviewAttention = {
  kind: 'device' | 'pending';
  reason: 'never-fetched' | 'behind-version' | 'stale' | 'pending' | 'pending-repeated';
  deviceId: string;
  label?: string;
  model?: string;
  currentVersion?: number;
  lastServedVersion?: number;
  lastServedAt?: string;
  requestCount?: number;
  lastSeenAt?: string;
};

export type OverviewModelCount = { model: string; count: number };

export type OverviewSummary = {
  generatedAt: string;
  staleWindowDays: number;
  cards: {
    registered: number;
    fetched24h: number;
    fetched7d: number;
    neverFetched: number;
    noRecentFetch: number;
    pending: number;
    configChanges24h: number;
  };
  storage: OverviewStorageHealth;
  devices: OverviewDevice[];
  attention: OverviewAttention[];
  recentAdminActivity: ActivityEvent[];
  modelDistribution: OverviewModelCount[];
};

export type BuildOverviewInput = {
  now?: Date | string | number;
  devices: StoredDevice[];
  deliveryStats?: Record<string, DeviceDeliveryStats | null | undefined>;
  pending: PendingDeviceRequest[];
  configChanges24h?: number | ActivityEvent[];
  recentAdminActivity?: ActivityEvent[];
  storage: OverviewStorageHealth;
  staleWindowMs?: number;
};

function normalizeNow(value: BuildOverviewInput['now']): number {
  if (value instanceof Date) return value.getTime();
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return Date.now();
}

/** Return true only for finite timestamps in the inclusive [now - window, now] range. */
export function isWithinWindow(value: string | undefined, now: Date | string | number, windowMs: number): boolean {
  if (!value || !Number.isFinite(windowMs) || windowMs < 0) return false;
  const timestamp = Date.parse(value);
  const current = normalizeNow(now);
  return Number.isFinite(timestamp) && timestamp >= current - windowMs && timestamp <= current;
}

/** Classify delivery using only HTTP fetch telemetry; this never implies client activation. */
export function classifyDeviceStatus(
  currentVersion: number,
  lastServedVersion: number | undefined,
  lastServedAt: string | undefined,
  now: Date | string | number,
  staleWindowMs = DEFAULT_STALE_WINDOW_MS
): DeviceFetchStatus {
  if (!lastServedAt) return 'never-fetched';
  if (typeof lastServedVersion === 'number' && lastServedVersion < currentVersion) return 'behind';
  if (!isWithinWindow(lastServedAt, now, staleWindowMs)) return 'stale';
  // A served event without a version is telemetry we cannot compare. It is
  // recent, but should not be described as behind or current-version delivery.
  return 'current';
}

function asCount(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.floor(value) : 0;
}

function configChangeCount(value: number | ActivityEvent[] | undefined) {
  if (Array.isArray(value)) return value.length;
  return asCount(value);
}

function attentionRank(item: OverviewAttention) {
  if (item.reason === 'never-fetched') return 0;
  if (item.reason === 'behind-version') return 1;
  if (item.reason === 'stale') return 2;
  if (item.reason === 'pending-repeated') return 3;
  return 4;
}

function attentionTimestamp(item: OverviewAttention) {
  const candidate = item.lastServedAt || item.lastSeenAt;
  const timestamp = candidate ? Date.parse(candidate) : 0;
  return Number.isFinite(timestamp) ? timestamp : 0;
}

/** Build all overview cards and safe display rows from storage snapshots. */
export function buildOverview(input: BuildOverviewInput): OverviewSummary {
  const nowMs = normalizeNow(input.now);
  const now = new Date(nowMs).toISOString();
  const staleWindowMs = Number.isFinite(input.staleWindowMs) && (input.staleWindowMs as number) >= 0
    ? Math.floor(input.staleWindowMs as number)
    : DEFAULT_STALE_WINDOW_MS;
  const stats = input.deliveryStats || {};
  const devices: OverviewDevice[] = input.devices.map((device) => {
    const candidateDelivery = stats[device.deviceId];
    // Telemetry from a previous profile must never be attributed to the new one.
    const delivery = candidateDelivery?.profileCreatedAt === device.createdAt ? candidateDelivery : undefined;
    const currentVersion = Number.isInteger(device.config.configVersion) ? device.config.configVersion : 0;
    return {
      deviceId: device.deviceId,
      label: device.label,
      model: device.model,
      createdAt: device.createdAt,
      updatedAt: device.updatedAt,
      currentVersion,
      ...(typeof delivery?.lastConfigVersionServed === 'number' ? { lastServedVersion: delivery.lastConfigVersionServed } : {}),
      ...(delivery?.firstRequestAt ? { firstRequestAt: delivery.firstRequestAt } : {}),
      ...(delivery?.lastRequestAt ? { lastRequestAt: delivery.lastRequestAt } : {}),
      ...(delivery?.lastServedAt ? { lastServedAt: delivery.lastServedAt } : {}),
      requestCount: asCount(delivery?.requestCount),
      servedCount: asCount(delivery?.servedCount),
      status: classifyDeviceStatus(currentVersion, delivery?.lastConfigVersionServed, delivery?.lastServedAt, nowMs, staleWindowMs)
    };
  });

  const fetched24h = devices.filter((device) => isWithinWindow(device.lastServedAt, nowMs, DAY_MS)).length;
  const fetched7d = devices.filter((device) => isWithinWindow(device.lastServedAt, nowMs, DEFAULT_STALE_WINDOW_MS)).length;
  const neverFetched = devices.filter((device) => device.status === 'never-fetched').length;
  const noRecentFetch = devices.filter((device) => device.status === 'stale').length;
  const attention: OverviewAttention[] = devices.flatMap((device): OverviewAttention[] => {
    if (device.status === 'never-fetched') return [{
      kind: 'device', reason: 'never-fetched', deviceId: device.deviceId, label: device.label,
      model: device.model, currentVersion: device.currentVersion
    } satisfies OverviewAttention];
    if (device.status === 'behind') return [{
      kind: 'device', reason: 'behind-version', deviceId: device.deviceId, label: device.label,
      model: device.model, currentVersion: device.currentVersion, lastServedVersion: device.lastServedVersion,
      ...(device.lastServedAt ? { lastServedAt: device.lastServedAt } : {})
    } satisfies OverviewAttention];
    if (device.status === 'stale') return [{
      kind: 'device', reason: 'stale', deviceId: device.deviceId, label: device.label,
      model: device.model, currentVersion: device.currentVersion, lastServedVersion: device.lastServedVersion,
      ...(device.lastServedAt ? { lastServedAt: device.lastServedAt } : {})
    } satisfies OverviewAttention];
    return [];
  });
  for (const pending of input.pending) {
    attention.push({
      kind: 'pending',
      reason: pending.requestCount > 1 ? 'pending-repeated' : 'pending',
      deviceId: pending.deviceId,
      requestCount: asCount(pending.requestCount),
      lastSeenAt: pending.lastSeenAt
    });
  }
  attention.sort((left, right) => attentionRank(left) - attentionRank(right) || attentionTimestamp(right) - attentionTimestamp(left) || left.deviceId.localeCompare(right.deviceId));

  const modelCounts = new Map<string, number>();
  for (const device of devices) modelCounts.set(device.model, (modelCounts.get(device.model) || 0) + 1);
  const modelDistribution = [...modelCounts.entries()]
    .map(([model, count]) => ({ model, count }))
    .sort((left, right) => right.count - left.count || left.model.localeCompare(right.model));

  return {
    generatedAt: now,
    staleWindowDays: Math.round(staleWindowMs / DAY_MS),
    cards: {
      registered: devices.length,
      fetched24h,
      fetched7d,
      neverFetched,
      noRecentFetch,
      pending: input.pending.length,
      configChanges24h: configChangeCount(input.configChanges24h)
    },
    storage: input.storage,
    devices,
    attention,
    recentAdminActivity: input.recentAdminActivity || [],
    modelDistribution
  };
}
