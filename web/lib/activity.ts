import { randomUUID } from 'node:crypto';
import type {
  ActivityAction,
  ActivityActor,
  ActivityCategory,
  ActivityEvent,
  ActivityResource,
  ActivityResult,
  SafeConfigChangeSummary,
  SafeConfigChangeSection,
  StoredDevice
} from './types';
import { kvGet, kvListPage, kvPut } from './storage';

export const ACTIVITY_PREFIX = 'activity:v1:';
const ACTIVITY_KEY_WIDTH = 13;
const MAX_SCAN = 1000;
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;
const THROTTLE_WINDOW_MS = 5 * 60 * 1000;
const MAX_THROTTLE_KEYS = 512;

const ADMIN_ACTIONS = new Set<ActivityAction>([
  'admin.login.succeeded', 'admin.logout', 'device.created', 'device.updated',
  'device.deleted', 'pending-request.dismissed', 'preset.created', 'preset.updated', 'preset.deleted'
]);
const DEVICE_ACTIONS = new Set<ActivityAction>([
  'config.request.succeeded', 'config.request.unknown-device',
  'config.request.invalid-device-id', 'config.request.failed'
]);
const SYSTEM_ACTIONS = new Set<ActivityAction>([
  'storage.unavailable', 'configuration.validation.failed', 'activity-log.write.failed'
]);
const CATEGORIES = new Set<ActivityCategory>(['administrator', 'device-configuration', 'system']);
const RESULTS = new Set<ActivityResult>(['succeeded', 'served', 'not-found', 'invalid', 'failed']);
const RESOURCE_TYPES = new Set<ActivityResource['type']>(['device', 'pending-device', 'preset', 'system']);

export type ActivityActorInput = ActivityActor;
export type ActivityResourceInput = ActivityResource;

export type ActivityEventInput = Omit<ActivityEvent, 'schemaVersion' | 'id' | 'occurredAt' | 'correlationId'> & {
  occurredAt?: string;
};

export type AdminActivityInput = {
  action: Extract<ActivityAction, 'admin.login.succeeded' | 'admin.logout' | 'device.created' | 'device.updated' | 'device.deleted' | 'pending-request.dismissed' | 'preset.created' | 'preset.updated' | 'preset.deleted'>;
  administrator: string;
  result?: Extract<ActivityResult, 'succeeded' | 'failed'>;
  resource: ActivityResourceInput;
  configVersions?: ActivityEvent['configVersions'];
  change?: SafeConfigChangeSummary;
};

export type ConfigRequestActivityInput = {
  action?: Extract<ActivityAction, 'config.request.succeeded' | 'config.request.unknown-device' | 'config.request.invalid-device-id' | 'config.request.failed'>;
  result: ActivityResult;
  deviceId?: string;
  label?: string;
  model?: string;
  configVersion?: number;
  profileCreatedAt?: string;
};

function safeString(value: unknown, max = 256) {
  return typeof value === 'string' && value.length > 0 && value.length <= max ? value : undefined;
}

function safeInteger(value: unknown) {
  return Number.isInteger(value) && Number(value) >= 0 && Number(value) <= Number.MAX_SAFE_INTEGER
    ? Number(value)
    : undefined;
}

function safeCount(value: unknown) {
  const parsed = safeInteger(value);
  return parsed === undefined ? undefined : Math.min(parsed, 1000000);
}

function safeDate(value: unknown) {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) return undefined;
  return new Date(value).toISOString();
}

function safeAction(value: unknown): ActivityAction | undefined {
  if (typeof value !== 'string') return undefined;
  const action = value as ActivityAction;
  return ADMIN_ACTIONS.has(action) || DEVICE_ACTIONS.has(action) || SYSTEM_ACTIONS.has(action) ? action : undefined;
}

function safeActor(value: unknown): ActivityActor | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const source = value as Record<string, unknown>;
  const type = source.type;
  if (type !== 'administrator' && type !== 'device' && type !== 'system') return undefined;
  const candidateUsername = safeString(source.username, 64);
  const username = candidateUsername && /^[A-Za-z0-9._-]{3,64}$/.test(candidateUsername)
    ? candidateUsername
    : undefined;
  return { type, ...(username ? { username } : {}) };
}

function safeResource(value: unknown): ActivityResource | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const source = value as Record<string, unknown>;
  const type = source.type;
  if (typeof type !== 'string' || !RESOURCE_TYPES.has(type as ActivityResource['type'])) return undefined;
  const rawId = safeString(source.id, 128);
  const id = rawId && (type === 'device' || type === 'pending-device'
    ? /^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6}$/.test(rawId)
    : /^[A-Za-z0-9._:-]{1,128}$/.test(rawId)) ? rawId : undefined;
  const label = safeString(source.label, 128);
  const rawModel = safeString(source.model, 64);
  const model = rawModel && /^[A-Za-z0-9._-]+$/.test(rawModel) ? rawModel : undefined;
  return { type: type as ActivityResource['type'], ...(id ? { id } : {}), ...(label ? { label } : {}), ...(model ? { model } : {}) };
}

function safeVersions(value: unknown): ActivityEvent['configVersions'] | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const source = value as Record<string, unknown>;
  const previous = safeInteger(source.previous);
  const current = safeInteger(source.current);
  const served = safeInteger(source.served);
  if (previous === undefined && current === undefined && served === undefined) return undefined;
  return {
    ...(previous !== undefined ? { previous } : {}),
    ...(current !== undefined ? { current } : {}),
    ...(served !== undefined ? { served } : {})
  };
}

function safeChange(value: unknown): SafeConfigChangeSummary | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const source = value as Record<string, unknown>;
  const allowedSections = new Set<SafeConfigChangeSection>([
    'label', 'model', 'connections', 'channels', 'radio', 'audio', 'tracking', 'management'
  ]);
  const sections = Array.isArray(source.sections)
    ? [...new Set(source.sections.filter((section): section is SafeConfigChangeSection => typeof section === 'string' && allowedSections.has(section as SafeConfigChangeSection)))]
    : [];
  const connectionsBefore = safeCount(source.connectionsBefore);
  const connectionsAfter = safeCount(source.connectionsAfter);
  const channelsBefore = safeCount(source.channelsBefore);
  const channelsAfter = safeCount(source.channelsAfter);
  if (sections.length === 0 && connectionsBefore === undefined && connectionsAfter === undefined
      && channelsBefore === undefined && channelsAfter === undefined) return undefined;
  return {
    sections,
    ...(connectionsBefore !== undefined ? { connectionsBefore } : {}),
    ...(connectionsAfter !== undefined ? { connectionsAfter } : {}),
    ...(channelsBefore !== undefined ? { channelsBefore } : {}),
    ...(channelsAfter !== undefined ? { channelsAfter } : {})
  };
}

/** Reconstruct only the allowlisted activity fields; all unknown input is dropped. */
export function parseStoredActivityEvent(value: unknown): ActivityEvent | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const source = value as Record<string, unknown>;
  if (source.schemaVersion !== 1) return null;
  const id = safeString(source.id, 128);
  const occurredAt = safeDate(source.occurredAt);
  const category = source.category;
  const action = safeAction(source.action);
  const actor = safeActor(source.actor);
  const result = source.result;
  if (!id || !occurredAt || typeof category !== 'string' || !CATEGORIES.has(category as ActivityCategory)
      || !action || !actor || typeof result !== 'string' || !RESULTS.has(result as ActivityResult)) return null;
  const expectedCategory = ADMIN_ACTIONS.has(action)
    ? 'administrator'
    : DEVICE_ACTIONS.has(action) ? 'device-configuration' : 'system';
  if (category !== expectedCategory) return null;
  const resource = safeResource(source.resource);
  const configVersions = safeVersions(source.configVersions);
  const change = safeChange(source.change);
  const correlationId = safeString(source.correlationId, 128);
  if (!resource || !correlationId) return null;
  const expectedActor = expectedCategory === 'administrator' ? 'administrator' : expectedCategory === 'device-configuration' ? 'device' : 'system';
  if (actor.type !== expectedActor || !resourceMatchesAction(action, resource.type)) return null;
  return {
    schemaVersion: 1,
    id,
    occurredAt,
    category: category as ActivityCategory,
    action,
    actor,
    resource,
    result: result as ActivityResult,
    correlationId,
    ...(configVersions ? { configVersions } : {}),
    ...(change ? { change } : {})
  };
}

function resourceMatchesAction(action: ActivityAction, resourceType: ActivityResource['type']) {
  if (action === 'admin.login.succeeded' || action === 'admin.logout'
      || action === 'config.request.invalid-device-id' || SYSTEM_ACTIONS.has(action)) return resourceType === 'system';
  if (action === 'pending-request.dismissed' || action === 'config.request.unknown-device') return resourceType === 'pending-device';
  if (action === 'preset.created' || action === 'preset.updated' || action === 'preset.deleted') return resourceType === 'preset';
  if (action === 'config.request.failed') return resourceType === 'device' || resourceType === 'system';
  return resourceType === 'device';
}

function retentionDays() {
  const configured = Number.parseInt(process.env.ACTIVITY_RETENTION_DAYS || '90', 10);
  if (!Number.isFinite(configured)) return 90;
  return Math.min(365, Math.max(30, configured));
}

function activityKey(occurredAt: string, id: string) {
  const millis = Date.parse(occurredAt);
  const reverse = Math.max(0, 9999999999999 - (Number.isFinite(millis) ? millis : Date.now()));
  return `${ACTIVITY_PREFIX}${String(reverse).padStart(ACTIVITY_KEY_WIDTH, '0')}:${id}`;
}

export function createActivityEvent(input: ActivityEventInput): ActivityEvent {
  const occurredAt = safeDate(input.occurredAt) || new Date().toISOString();
  const event: ActivityEvent = {
    schemaVersion: 1,
    id: randomUUID(),
    occurredAt,
    category: input.category,
    action: input.action,
    actor: input.actor,
    result: input.result,
    resource: input.resource,
    correlationId: randomUUID(),
    ...(input.configVersions ? { configVersions: input.configVersions } : {}),
    ...(input.change ? { change: input.change } : {})
  };
  const parsed = parseStoredActivityEvent(event);
  if (!parsed) throw new Error('Invalid activity event');
  return parsed;
}

export async function recordActivity(input: ActivityEventInput) {
  const event = createActivityEvent(input);
  await kvPut(activityKey(event.occurredAt, event.id), event, {
    expirationTtlSeconds: retentionDays() * 24 * 60 * 60
  });
  return event;
}

export async function recordAdminActivity(input: AdminActivityInput) {
  return recordActivity({
    category: 'administrator',
    action: input.action,
    actor: { type: 'administrator', username: input.administrator },
    result: input.result || 'succeeded',
    resource: input.resource,
    ...(input.configVersions ? { configVersions: input.configVersions } : {}),
    ...(input.change ? { change: input.change } : {})
  });
}

function actionForResult(result: ActivityResult): Extract<ActivityAction, 'config.request.succeeded' | 'config.request.unknown-device' | 'config.request.invalid-device-id' | 'config.request.failed'> {
  if (result === 'served' || result === 'succeeded') return 'config.request.succeeded';
  if (result === 'not-found') return 'config.request.unknown-device';
  if (result === 'invalid') return 'config.request.invalid-device-id';
  return 'config.request.failed';
}

export async function recordConfigRequestActivity(input: ConfigRequestActivityInput) {
  const action = input.action || actionForResult(input.result);
  const resource = input.deviceId
    ? {
      type: action === 'config.request.unknown-device' ? 'pending-device' as const : 'device' as const,
      id: input.deviceId,
      ...(input.label ? { label: input.label } : {}),
      ...(input.model ? { model: input.model } : {})
    }
    : undefined;
  return recordActivity({
    category: 'device-configuration',
    action,
    actor: { type: 'device' },
    result: input.result,
    resource: resource || { type: 'system' },
    ...(typeof input.configVersion === 'number' ? { configVersions: { served: input.configVersion } } : {})
  });
}

/** Bounded process-local suppression for repeated invalid/unknown requests. */
const throttledActivity = new Map<string, number>();
export async function recordThrottledConfigRequestActivity(input: ConfigRequestActivityInput, throttleKey: string) {
  const now = Date.now();
  const previous = throttledActivity.get(throttleKey);
  if (previous !== undefined && now - previous < THROTTLE_WINDOW_MS) return null;
  throttledActivity.set(throttleKey, now);
  if (throttledActivity.size > MAX_THROTTLE_KEYS) {
    const oldest = [...throttledActivity.entries()].sort((left, right) => left[1] - right[1])[0];
    if (oldest) throttledActivity.delete(oldest[0]);
  }
  return recordConfigRequestActivity(input);
}

export function resetActivityThrottle() {
  throttledActivity.clear();
}

function canonicalFilterValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalFilterValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, nested]) => [key, canonicalFilterValue(nested)]));
  }
  return value;
}

export type ActivityFilters = {
  from?: string;
  to?: string;
  deviceId?: string;
  label?: string;
  administrator?: string;
  category?: ActivityCategory;
  action?: ActivityAction;
  result?: ActivityResult;
  model?: string;
  configVersion?: number;
};

export type ActivityPage = {
  events: ActivityEvent[];
  nextCursor?: string;
};

type ActivityCursor = {
  v: 1;
  kvCursor?: string;
  offset: number;
  fingerprint: string;
};

function filterFingerprint(filters: ActivityFilters) {
  return JSON.stringify(canonicalFilterValue(filters));
}

function encodeCursor(value: ActivityCursor) {
  return Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
}

function decodeCursor(value: string | undefined, filters: ActivityFilters): ActivityCursor {
  if (!value) return { v: 1, offset: 0, fingerprint: filterFingerprint(filters) };
  try {
    const parsed = JSON.parse(Buffer.from(value, 'base64url').toString('utf8')) as Partial<ActivityCursor>;
    if (parsed.v !== 1 || !Number.isInteger(parsed.offset) || (parsed.offset as number) < 0
        || typeof parsed.fingerprint !== 'string' || parsed.fingerprint !== filterFingerprint(filters)) {
      throw new Error('Invalid activity cursor');
    }
    return {
      v: 1,
      ...(typeof parsed.kvCursor === 'string' && parsed.kvCursor ? { kvCursor: parsed.kvCursor } : {}),
      offset: parsed.offset as number,
      fingerprint: parsed.fingerprint
    };
  } catch {
    throw new Error('Invalid activity cursor');
  }
}

function eventMatches(event: ActivityEvent, filters: ActivityFilters) {
  const eventMs = Date.parse(event.occurredAt);
  if (filters.from && eventMs < Date.parse(filters.from)) return false;
  if (filters.to && eventMs > Date.parse(filters.to)) return false;
  const resource = event.resource;
  if (filters.deviceId && resource?.id !== filters.deviceId) return false;
  if (filters.label && resource?.label !== filters.label) return false;
  if (filters.administrator && event.actor.username !== filters.administrator) return false;
  if (filters.category && event.category !== filters.category) return false;
  if (filters.action && event.action !== filters.action) return false;
  if (filters.result && event.result !== filters.result) return false;
  if (filters.model && resource?.model !== filters.model) return false;
  if (filters.configVersion !== undefined) {
    const versions = event.configVersions;
    if (versions?.previous !== filters.configVersion && versions?.current !== filters.configVersion && versions?.served !== filters.configVersion) return false;
  }
  return true;
}

/** Read reverse-chronological activity pages with an opaque stable cursor. */
export async function listActivityPage(filters: ActivityFilters = {}, options: { limit?: number; cursor?: string } = {}): Promise<ActivityPage> {
  const limit = Math.min(MAX_LIMIT, Math.max(1, Math.floor(options.limit || DEFAULT_LIMIT)));
  const cursor = decodeCursor(options.cursor, filters);
  let kvCursor = cursor.kvCursor;
  let offset = cursor.offset;
  const events: ActivityEvent[] = [];
  let scanned = 0;
  let hasMore = false;

  while (scanned < MAX_SCAN && events.length < limit) {
    const batch = await kvListPage(ACTIVITY_PREFIX, { limit: MAX_SCAN, cursor: kvCursor });
    if (offset > batch.keys.length) throw new Error('Invalid activity cursor');
    while (offset < batch.keys.length && scanned < MAX_SCAN) {
      const key = batch.keys[offset];
      offset += 1;
      scanned += 1;
      const stored = await kvGet<unknown>(key);
      const event = parseStoredActivityEvent(stored);
      if (event && eventMatches(event, filters)) events.push(event);
      if (events.length >= limit) break;
    }
    if (events.length >= limit) {
      // A cursor points at the first unread key. If the current page was
      // consumed exactly, advance to the provider cursor now rather than
      // returning a cursor that makes the next request re-read this page.
      if (offset < batch.keys.length) {
        hasMore = true;
      } else if (batch.cursor) {
        kvCursor = batch.cursor;
        offset = 0;
        hasMore = true;
      } else {
        kvCursor = undefined;
        offset = 0;
      }
      break;
    }
    // The scan safety budget can end in the middle of a page. Preserve both
    // the cursor used for this page and the exact offset so selective filters
    // can resume without skipping older matches.
    if (offset < batch.keys.length) {
      hasMore = true;
      break;
    }
    if (!batch.cursor) {
      kvCursor = undefined;
      offset = 0;
      hasMore = false;
      break;
    }
    kvCursor = batch.cursor;
    offset = 0;
    // We consumed a full provider page without filling the result limit. If
    // the request budget is now exhausted, expose the provider cursor so the
    // next page can continue the filtered scan.
    if (scanned >= MAX_SCAN) {
      hasMore = true;
      break;
    }
  }
  if (!hasMore) return { events };
  return {
    events,
    nextCursor: encodeCursor({
      v: 1,
      ...(kvCursor ? { kvCursor } : {}),
      offset,
      fingerprint: filterFingerprint(filters)
    })
  };
}

/** Count only safe, aggregate changes between two stored profiles. */
export function summarizeDeviceChange(previous: StoredDevice | null | undefined, next: StoredDevice): SafeConfigChangeSummary {
  const oldConfig = previous?.config as Record<string, unknown> | undefined;
  const newConfig = next.config as Record<string, unknown>;
  const oldConnections = oldConfig && oldConfig.connections && typeof oldConfig.connections === 'object' && !Array.isArray(oldConfig.connections)
    ? oldConfig.connections as Record<string, unknown> : {};
  const newConnections = newConfig.connections && typeof newConfig.connections === 'object' && !Array.isArray(newConfig.connections)
    ? newConfig.connections as Record<string, unknown> : {};
  const oldChannels = Array.isArray(oldConfig?.channels) ? oldConfig.channels : [];
  const newChannels = Array.isArray(newConfig.channels) ? newConfig.channels : [];
  const sections: SafeConfigChangeSection[] = [];
  if (previous && previous.label !== next.label) sections.push('label');
  if (previous && previous.model !== next.model) sections.push('model');
  const sectionKeys: Array<Exclude<SafeConfigChangeSection, 'label' | 'model'>> = [
    'connections', 'channels', 'radio', 'audio', 'tracking', 'management'
  ];
  for (const key of sectionKeys) {
    if (JSON.stringify(oldConfig?.[key]) !== JSON.stringify(newConfig[key])) sections.push(key);
  }
  return {
    sections,
    ...(previous ? { connectionsBefore: Object.keys(oldConnections).length } : {}),
    connectionsAfter: Object.keys(newConnections).length,
    ...(previous ? { channelsBefore: oldChannels.length } : {}),
    channelsAfter: newChannels.length
  };
}
