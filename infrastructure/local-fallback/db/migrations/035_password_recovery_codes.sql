CREATE TABLE IF NOT EXISTS fallback_password_recovery_codes (
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  used_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, code_hash)
);

CREATE INDEX IF NOT EXISTS idx_fallback_password_recovery_unused
  ON fallback_password_recovery_codes(user_id) WHERE used_at IS NULL;
