CREATE TABLE IF NOT EXISTS login_rate_limit_v1 (
  bucket_hash TEXT PRIMARY KEY,
  attempts INTEGER NOT NULL CHECK (attempts >= 1),
  reset_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS login_rate_limit_v1_reset_at
  ON login_rate_limit_v1 (reset_at);

CREATE TRIGGER IF NOT EXISTS login_rate_limit_v1_prune_after_insert
AFTER INSERT ON login_rate_limit_v1
BEGIN
  DELETE FROM login_rate_limit_v1
  WHERE reset_at < unixepoch() - 86400;
END;

CREATE TRIGGER IF NOT EXISTS login_rate_limit_v1_prune_after_update
AFTER UPDATE ON login_rate_limit_v1
BEGIN
  DELETE FROM login_rate_limit_v1
  WHERE reset_at < unixepoch() - 86400;
END;
