import type {
  DeviceDeliveryStats,
  DismissedPendingDevice,
  PendingDeviceRequest,
  StoredAdmin,
  StoredDevice
} from './types';

declare global {
  // Shared across Next development route bundles; production never uses this store.
  var __minimumKvMemoryStore: Map<string, string> | undefined;
  var __minimumKvMemoryExpiry: Map<string, number> | undefined;
}

const memory = globalThis.__minimumKvMemoryStore || new Map<string, string>();
if (process.env.NODE_ENV !== 'production') globalThis.__minimumKvMemoryStore = memory;
const memoryExpiry = globalThis.__minimumKvMemoryExpiry || new Map<string, number>();
if (process.env.NODE_ENV !== 'production') globalThis.__minimumKvMemoryExpiry = memoryExpiry;

type CloudflareConfig = {
  accountId: string;
  apiToken: string;
  namespaceId: string;
  baseUrl: string;
};

const PENDING_DEVICE_PREFIX = 'pending-device:';
const PENDING_DEVICE_TTL_SECONDS = 24 * 60 * 60;
const DISMISSED_PENDING_DEVICE_PREFIX = 'dismissed-pending-device:';
const DISMISSED_PENDING_DEVICE_TTL_SECONDS = 24 * 60 * 60;
const DEVICE_DELIVERY_PREFIX = 'device-delivery:v1:';
const DEVICE_DELIVERY_TTL_SECONDS = 90 * 24 * 60 * 60;

export type KvListPage = {
  keys: string[];
  /** Provider-compatible opaque cursor. `undefined` means the page is complete. */
  cursor?: string;
};

type KvListPageOptions = {
  limit?: number;
  cursor?: string;
};

function cloudflareConfig(): CloudflareConfig | null {
  const accountId = process.env.CLOUDFLARE_ACCOUNT_ID;
  const apiToken = process.env.CLOUDFLARE_API_TOKEN;
  const namespaceId = process.env.CLOUDFLARE_KV_NAMESPACE_ID;
  if (!accountId || !apiToken || !namespaceId) {
    if (process.env.NODE_ENV !== 'production') return null;
    throw new Error('Cloudflare KV is not configured');
  }
  return {
    accountId,
    apiToken,
    namespaceId,
    baseUrl: process.env.CLOUDFLARE_KV_API_BASE || 'https://api.cloudflare.com/client/v4'
  };
}

function kvUrl(config: CloudflareConfig, suffix: string) {
  return `${config.baseUrl}/accounts/${encodeURIComponent(config.accountId)}/storage/kv/namespaces/${encodeURIComponent(config.namespaceId)}${suffix}`;
}

async function kvRequest(config: CloudflareConfig, suffix: string, init?: RequestInit) {
  return fetch(kvUrl(config, suffix), {
    ...init,
    headers: {
      Authorization: `Bearer ${config.apiToken}`,
      ...(init?.headers || {})
    },
    cache: 'no-store'
  });
}

export async function kvGet<T>(key: string): Promise<T | null> {
  const config = cloudflareConfig();
  if (!config) {
    const expiresAt = memoryExpiry.get(key);
    if (expiresAt !== undefined && expiresAt <= Date.now()) {
      memoryExpiry.delete(key);
      memory.delete(key);
      return null;
    }
    const value = memory.get(key);
    return value ? JSON.parse(value) as T : null;
  }
  const response = await kvRequest(config, `/values/${encodeURIComponent(key)}`);
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Cloudflare KV read failed (${response.status})`);
  return JSON.parse(await response.text()) as T;
}

export async function kvPut<T>(key: string, value: T, options?: { expirationTtlSeconds?: number }) {
  const serialized = JSON.stringify(value);
  const config = cloudflareConfig();
  if (!config) {
    memory.set(key, serialized);
    if (options?.expirationTtlSeconds !== undefined) {
      memoryExpiry.set(key, Date.now() + Math.max(0, options.expirationTtlSeconds) * 1000);
    } else {
      memoryExpiry.delete(key);
    }
    return;
  }
  const suffix = options?.expirationTtlSeconds
    ? `/values/${encodeURIComponent(key)}?expiration_ttl=${Math.max(60, Math.floor(options.expirationTtlSeconds))}`
    : `/values/${encodeURIComponent(key)}`;
  const response = await kvRequest(config, suffix, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: serialized
  });
  if (!response.ok) throw new Error(`Cloudflare KV write failed (${response.status})`);
}

export async function kvDelete(key: string) {
  const config = cloudflareConfig();
  if (!config) {
    memoryExpiry.delete(key);
    memory.delete(key);
    return;
  }
  const response = await kvRequest(config, `/values/${encodeURIComponent(key)}`, { method: 'DELETE' });
  if (!response.ok && response.status !== 404) throw new Error(`Cloudflare KV delete failed (${response.status})`);
}

export async function kvList(prefix: string) {
  const keys: string[] = [];
  let cursor: string | undefined;
  do {
    const page = await kvListPage(prefix, { limit: 1000, cursor });
    keys.push(...page.keys);
    cursor = page.cursor;
    if (keys.length > 10000) throw new Error('Cloudflare KV key listing exceeds safety limit');
  } while (cursor);
  return keys;
}

function normalizeListLimit(value: number | undefined) {
  if (!Number.isFinite(value)) return 1000;
  return Math.min(1000, Math.max(1, Math.floor(value as number)));
}

function encodeMemoryCursor(index: number) {
  return Buffer.from(JSON.stringify({ v: 1, index }), 'utf8').toString('base64url');
}

function decodeMemoryCursor(cursor: string | undefined) {
  if (!cursor) return 0;
  try {
    const parsed = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')) as { v?: number; index?: number };
    if (parsed.v !== 1 || !Number.isInteger(parsed.index) || parsed.index! < 0) throw new Error('invalid cursor');
    return parsed.index!;
  } catch {
    throw new Error('Invalid KV list cursor');
  }
}

/** List one deterministic page of keys while preserving Cloudflare's cursor contract. */
export async function kvListPage(prefix: string, options: KvListPageOptions = {}): Promise<KvListPage> {
  const limit = normalizeListLimit(options.limit);
  const config = cloudflareConfig();
  if (!config) {
    const now = Date.now();
    const keys: string[] = [];
    for (const key of memory.keys()) {
      if (!key.startsWith(prefix)) continue;
      const expiresAt = memoryExpiry.get(key);
      if (expiresAt !== undefined && expiresAt <= now) {
        memoryExpiry.delete(key);
        memory.delete(key);
        continue;
      }
      keys.push(key);
    }
    keys.sort((left, right) => left < right ? -1 : left > right ? 1 : 0);
    const start = decodeMemoryCursor(options.cursor);
    if (start > keys.length) throw new Error('Invalid KV list cursor');
    const page = keys.slice(start, start + limit);
    const next = start + page.length;
    return { keys: page, ...(next < keys.length ? { cursor: encodeMemoryCursor(next) } : {}) };
  }

  const query = new URLSearchParams({ prefix, limit: String(limit) });
  if (options.cursor) query.set('cursor', options.cursor);
  const response = await kvRequest(config, `/keys?${query.toString()}`);
  if (!response.ok) throw new Error(`Cloudflare KV list failed (${response.status})`);
  const body = await response.json() as {
    result?: Array<{ name: string }>;
    result_info?: { cursor?: string };
  };
  const nextCursor = body.result_info?.cursor || undefined;
  return {
    keys: (body.result || []).map((item) => item.name).sort((left, right) => left < right ? -1 : left > right ? 1 : 0),
    ...(nextCursor ? { cursor: nextCursor } : {})
  };
}

export async function getAdmin() {
  return kvGet<StoredAdmin>('admin:account');
}

export async function putAdmin(admin: StoredAdmin) {
  return kvPut('admin:account', admin);
}

export async function getDevice(deviceId: string) {
  const key = `device:${deviceId}`;
  const stored = await kvGet<StoredDevice & { tokenHash?: string; tokenCreatedAt?: string }>(key);
  if (!stored) return null;
  const { tokenHash, tokenCreatedAt, ...device } = stored;
  if (tokenHash !== undefined || tokenCreatedAt !== undefined) {
    await kvPut(key, device);
  }
  return device;
}

export async function putDevice(device: StoredDevice) {
  return kvPut(`device:${device.deviceId}`, device);
}

export async function deleteDevice(deviceId: string) {
  return kvDelete(`device:${deviceId}`);
}

function deviceDeliveryKey(deviceId: string) {
  return `${DEVICE_DELIVERY_PREFIX}${deviceId}`;
}

export async function listDevices() {
  const keys = await kvList('device:');
  const devices = await Promise.all(keys.map((key) => getDevice(key.slice('device:'.length))));
  return devices.filter((device): device is StoredDevice => Boolean(device));
}

/** Return best-effort HTTP configuration delivery counters for one profile. */
export async function getDeviceDeliveryStats(deviceId: string) {
  return kvGet<DeviceDeliveryStats>(deviceDeliveryKey(deviceId));
}

export type DeviceConfigRequestOptions = {
  profileCreatedAt: string;
  requestedAt?: string;
  served?: boolean;
  configVersionServed?: number;
};

/**
 * Update delivery counters without letting an older event move timestamps
 * backwards. Reads and writes are deliberately non-atomic because Cloudflare
 * KV has no compare-and-swap primitive; callers must treat this as telemetry.
 */
export async function recordDeviceConfigRequest(
  deviceId: string,
  options: DeviceConfigRequestOptions
) {
  const at = options.requestedAt || new Date().toISOString();
  const current = await getDeviceDeliveryStats(deviceId);
  const profileChanged = current?.profileCreatedAt !== options.profileCreatedAt;
  const previous: DeviceDeliveryStats = profileChanged || !current
    ? {
      deviceId,
      profileCreatedAt: options.profileCreatedAt,
      requestCount: 0,
      servedCount: 0
    }
    : current;
  const next: DeviceDeliveryStats = {
    ...previous,
    deviceId,
    profileCreatedAt: options.profileCreatedAt,
    firstRequestAt: previous.firstRequestAt && Date.parse(previous.firstRequestAt) <= Date.parse(at)
      ? previous.firstRequestAt
      : at,
    lastRequestAt: newerTimestamp(previous.lastRequestAt, at) || at,
    requestCount: Math.min(1000000, Math.max(0, previous.requestCount || 0) + 1),
    servedCount: previous.servedCount || 0
  };
  if (options.served) {
    next.servedCount = Math.min(1000000, next.servedCount + 1);
    next.lastServedAt = newerTimestamp(previous.lastServedAt, at) || at;
    if (typeof options.configVersionServed === 'number' && Number.isInteger(options.configVersionServed)) {
      // A late response must not replace a newer served version.
      if (!previous.lastServedAt || Date.parse(at) >= Date.parse(previous.lastServedAt)) {
        next.lastConfigVersionServed = options.configVersionServed;
      } else {
        next.lastConfigVersionServed = previous.lastConfigVersionServed;
      }
    }
  }
  await kvPut(deviceDeliveryKey(deviceId), next, { expirationTtlSeconds: DEVICE_DELIVERY_TTL_SECONDS });
  return next;
}

function newerTimestamp(previous: string | undefined, candidate: string) {
  if (!previous) return candidate;
  const previousMs = Date.parse(previous);
  const candidateMs = Date.parse(candidate);
  if (!Number.isFinite(previousMs) || !Number.isFinite(candidateMs)) return previous;
  return candidateMs >= previousMs ? candidate : previous;
}

export async function deleteDeviceDeliveryStats(deviceId: string) {
  return kvDelete(deviceDeliveryKey(deviceId));
}

/** Read-only storage sentinel used by future operational summaries. */
export async function checkStorageHealth() {
  const started = Date.now();
  try {
    // Reading a reserved key exercises the configured provider without
    // mutating application state. A missing key is a healthy read.
    await kvGet<unknown>('storage:health:sentinel');
    return {
      ok: true,
      backend: cloudflareConfig() ? 'cloudflare' as const : 'memory' as const,
      latencyMs: Date.now() - started
    };
  } catch {
    return {
      ok: false,
      backend: (() => {
        try { return cloudflareConfig() ? 'cloudflare' as const : 'memory' as const; } catch { return 'cloudflare' as const; }
      })(),
      latencyMs: Date.now() - started
    };
  }
}

export async function recordPendingDeviceRequest(deviceId: string) {
  if (await isPendingDeviceDismissed(deviceId)) return;
  const now = new Date().toISOString();
  const existing = await kvGet<PendingDeviceRequest>(`${PENDING_DEVICE_PREFIX}${deviceId}`);
  const pending: PendingDeviceRequest = {
    deviceId,
    firstSeenAt: existing?.firstSeenAt || now,
    lastSeenAt: now,
    requestCount: Math.min((existing?.requestCount || 0) + 1, 100000)
  };
  await kvPut(`${PENDING_DEVICE_PREFIX}${deviceId}`, pending, {
    expirationTtlSeconds: PENDING_DEVICE_TTL_SECONDS
  });
}

export async function listPendingDeviceRequests() {
  const keys = await kvList(PENDING_DEVICE_PREFIX);
  const entries = await Promise.all(keys.map(async (key) => {
    const entry = await kvGet<PendingDeviceRequest>(key);
    // Cloudflare KV has eventual consistency, so this list-side filtering is
    // the final mitigation for a marker/device write that has not propagated
    // to the same read replica yet. It is not an atomic transaction.
    if (!entry || await isPendingDeviceDismissed(entry.deviceId)) return null;
    if (await deviceRecordExists(entry.deviceId)) return null;
    return entry;
  }));
  const cutoff = Date.now() - PENDING_DEVICE_TTL_SECONDS * 1000;
  return entries
    .filter((entry): entry is PendingDeviceRequest => entry !== null
      && Number.isFinite(Date.parse(entry.lastSeenAt))
      && Date.parse(entry.lastSeenAt) >= cutoff)
    .sort((left, right) => Date.parse(right.lastSeenAt) - Date.parse(left.lastSeenAt))
    .slice(0, 100);
}

export async function deletePendingDeviceRequest(deviceId: string) {
  return kvDelete(`${PENDING_DEVICE_PREFIX}${deviceId}`);
}

function dismissedPendingDeviceKey(deviceId: string) {
  return `${DISMISSED_PENDING_DEVICE_PREFIX}${deviceId}`;
}

async function deviceRecordExists(deviceId: string) {
  return (await kvGet<unknown>(`device:${deviceId}`)) !== null;
}

export async function getDismissedPendingDevice(deviceId: string) {
  const key = dismissedPendingDeviceKey(deviceId);
  const marker = await kvGet<DismissedPendingDevice>(key);
  if (!marker) return null;
  const expiresAt = Date.parse(marker.expiresAt);
  if (marker.deviceId !== deviceId || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    await kvDelete(key);
    return null;
  }
  return marker;
}

export async function isPendingDeviceDismissed(deviceId: string) {
  return (await getDismissedPendingDevice(deviceId)) !== null;
}

export async function clearDismissedPendingDevice(deviceId: string) {
  return kvDelete(dismissedPendingDeviceKey(deviceId));
}

type DismissPendingDeviceOperations = {
  deviceRecordExists: (deviceId: string) => Promise<boolean>;
  getDismissedPendingDevice: (deviceId: string) => Promise<DismissedPendingDevice | null>;
  writeDismissedPendingDevice: (marker: DismissedPendingDevice) => Promise<void>;
  clearDismissedPendingDevice: (deviceId: string) => Promise<void>;
  deletePendingDeviceRequest: (deviceId: string) => Promise<void>;
};

export type DismissPendingDeviceOverrides = Partial<DismissPendingDeviceOperations>;

async function writeDismissedPendingDevice(marker: DismissedPendingDevice) {
  await kvPut(dismissedPendingDeviceKey(marker.deviceId), marker, {
    expirationTtlSeconds: DISMISSED_PENDING_DEVICE_TTL_SECONDS
  });
}

export async function dismissPendingDeviceRequest(
  deviceId: string,
  overrides: DismissPendingDeviceOverrides = {}
) {
  const operations: DismissPendingDeviceOperations = {
    deviceRecordExists,
    getDismissedPendingDevice,
    writeDismissedPendingDevice,
    clearDismissedPendingDevice,
    deletePendingDeviceRequest,
    ...overrides
  };

  if (await operations.deviceRecordExists(deviceId)) {
    // A registered record wins over inbound suppression. Remove only stale
    // pending state; never create a marker that could suppress this radio.
    // Cleanup is advisory because the device record is already authoritative.
    await operations.clearDismissedPendingDevice(deviceId).catch(() => undefined);
    await operations.deletePendingDeviceRequest(deviceId).catch(() => undefined);
    return;
  }

  // Preserve an active marker on repeated DELETEs so dismissal remains
  // idempotent and does not silently extend the suppression window.
  let markerEstablished = await operations.getDismissedPendingDevice(deviceId) !== null;
  if (!markerEstablished) {
    const dismissedAt = new Date();
    const marker: DismissedPendingDevice = {
      deviceId,
      dismissedAt: dismissedAt.toISOString(),
      expiresAt: new Date(dismissedAt.getTime() + DISMISSED_PENDING_DEVICE_TTL_SECONDS * 1000).toISOString()
    };
    // Marker-first is deliberate: if its PUT fails, leave the pending row in
    // place so the caller receives a safe 503 and can retry. Once the marker
    // is established, dismissal is effective even if cleanup below fails.
    await operations.writeDismissedPendingDevice(marker);
    markerEstablished = true;
  }

  // Cloudflare KV does not provide an atomic transaction across device,
  // marker and pending keys. Re-checking after marker establishment narrows
  // the registration/dismiss race; list-side filtering and registered
  // delivery cleanup provide the remaining eventual-consistency mitigation.
  if (markerEstablished) {
    try {
      if (await operations.deviceRecordExists(deviceId)) {
        await operations.clearDismissedPendingDevice(deviceId).catch(() => undefined);
      }
    } catch {
      // The marker is still an effective dismissal if this eventual read is
      // unavailable. A later registered config delivery will self-heal it.
    }
  }

  // Pending deletion is best effort after marker establishment. Returning a
  // 503 here would make the UI report failure while a subsequent refresh
  // correctly hides the row due to the marker.
  await operations.deletePendingDeviceRequest(deviceId).catch(() => undefined);
}

export function resetMemoryStore() {
  memory.clear();
  memoryExpiry.clear();
}
