const D1_API_ROOT = 'https://api.cloudflare.com/client/v4';
const D1_REQUEST_TIMEOUT_MS = 2_000;

export interface LoginRateLimitRecord {
  bucketHash: string;
  attempts: number;
  resetAt: number;
  observedAt: number;
}

export interface D1LoginRateLimitConfig {
  accountId: string;
  databaseId: string;
  apiToken: string;
}

export interface LoginRateLimitStorageOptions {
  backend?: 'd1' | 'memory';
  nowSeconds?: number;
  fetchImpl?: typeof fetch;
  d1Config?: D1LoginRateLimitConfig;
}

interface MemoryRecord {
  attempts: number;
  resetAt: number;
}

interface D1Row {
  bucket_hash?: unknown;
  attempts?: unknown;
  reset_at?: unknown;
  observed_at?: unknown;
}

const memoryRecords = new Map<string, MemoryRecord>();

export class LoginRateLimitUnavailableError extends Error {
  constructor() {
    super('Login rate limit storage is unavailable');
    this.name = 'LoginRateLimitUnavailableError';
  }
}

function unavailable(): never {
  throw new LoginRateLimitUnavailableError();
}

function validateInputs(bucketHash: string, windowSeconds: number) {
  if (
    !/^v1:[A-Za-z0-9_-]{43}$/.test(bucketHash)
    || !Number.isSafeInteger(windowSeconds)
    || windowSeconds < 1
    || windowSeconds > 24 * 60 * 60
  ) unavailable();
}

function productionD1Config(): D1LoginRateLimitConfig {
  const accountId = process.env.CLOUDFLARE_ACCOUNT_ID;
  const databaseId = process.env.CLOUDFLARE_D1_DATABASE_ID;
  const apiToken = process.env.CLOUDFLARE_D1_API_TOKEN;
  if (!accountId || !databaseId || !apiToken) unavailable();
  return { accountId, databaseId, apiToken };
}

function reserveInMemory(
  bucketHash: string,
  windowSeconds: number,
  nowSeconds = Math.floor(Date.now() / 1_000)
) {
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 0) unavailable();
  const current = memoryRecords.get(bucketHash);
  const next = !current || current.resetAt <= nowSeconds
    ? { attempts: 1, resetAt: nowSeconds + windowSeconds }
    : { attempts: current.attempts + 1, resetAt: current.resetAt };
  memoryRecords.set(bucketHash, next);
  return { bucketHash, ...next, observedAt: nowSeconds };
}

function admissionSql(windowSeconds: number) {
  return `
INSERT INTO login_rate_limit_v1 (bucket_hash, attempts, reset_at)
VALUES (?, 1, unixepoch() + ${windowSeconds})
ON CONFLICT(bucket_hash) DO UPDATE SET
  attempts = CASE
    WHEN login_rate_limit_v1.reset_at <= unixepoch() THEN 1
    ELSE login_rate_limit_v1.attempts + 1
  END,
  reset_at = CASE
    WHEN login_rate_limit_v1.reset_at <= unixepoch() THEN unixepoch() + ${windowSeconds}
    ELSE login_rate_limit_v1.reset_at
  END
RETURNING bucket_hash, attempts, reset_at, unixepoch() AS observed_at;`.trim();
}

function parseD1Record(value: unknown, expectedHash: string) {
  if (!value || typeof value !== 'object') unavailable();
  const envelope = value as {
    success?: unknown;
    result?: unknown;
  };
  if (envelope.success !== true || !Array.isArray(envelope.result) || envelope.result.length !== 1) unavailable();
  const query = envelope.result[0] as { success?: unknown; results?: unknown } | undefined;
  if (!query || query.success !== true || !Array.isArray(query.results)) unavailable();

  const records = (query.results as D1Row[]).map((row): LoginRateLimitRecord => {
    if (
      typeof row.bucket_hash !== 'string'
      || !Number.isSafeInteger(row.attempts)
      || !Number.isSafeInteger(row.reset_at)
      || !Number.isSafeInteger(row.observed_at)
    ) unavailable();
    return {
      bucketHash: row.bucket_hash,
      attempts: row.attempts as number,
      resetAt: row.reset_at as number,
      observedAt: row.observed_at as number
    };
  });

  if (
    records.length !== 1
    || records.some((record) => record.attempts < 1 || record.resetAt <= record.observedAt)
    || records[0]?.bucketHash !== expectedHash
  ) unavailable();
  return records[0];
}

async function reserveInD1(
  bucketHash: string,
  windowSeconds: number,
  config: D1LoginRateLimitConfig,
  fetchImpl: typeof fetch
) {
  if (!config.accountId || !config.databaseId || !config.apiToken) unavailable();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), D1_REQUEST_TIMEOUT_MS);
  try {
    const response = await fetchImpl(
      `${D1_API_ROOT}/accounts/${encodeURIComponent(config.accountId)}/d1/database/${encodeURIComponent(config.databaseId)}/query`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${config.apiToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          sql: admissionSql(windowSeconds),
          params: [bucketHash]
        }),
        signal: controller.signal
      }
    );
    if (!response.ok) unavailable();
    return parseD1Record(await response.json(), bucketHash);
  } catch (error) {
    if (error instanceof LoginRateLimitUnavailableError) throw error;
    unavailable();
  } finally {
    clearTimeout(timeout);
  }
}

export async function reserveLoginRateLimitBucket(
  bucketHash: string,
  windowSeconds: number,
  options: LoginRateLimitStorageOptions = {}
) {
  validateInputs(bucketHash, windowSeconds);
  const backend = options.backend ?? (process.env.NODE_ENV === 'production' ? 'd1' : 'memory');
  if (backend === 'memory') return reserveInMemory(bucketHash, windowSeconds, options.nowSeconds);
  return reserveInD1(
    bucketHash,
    windowSeconds,
    options.d1Config ?? productionD1Config(),
    options.fetchImpl ?? fetch
  );
}

export function resetLoginRateLimitMemoryStore() {
  memoryRecords.clear();
}
