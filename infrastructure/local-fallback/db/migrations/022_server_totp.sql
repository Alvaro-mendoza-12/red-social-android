CREATE TABLE IF NOT EXISTS fallback_totp_factors (
  user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
  secret_ciphertext TEXT,
  pending_ciphertext TEXT,
  enabled_at TIMESTAMPTZ,
  last_used_step BIGINT NOT NULL DEFAULT -1,
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_totp_recovery_codes (
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  used_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, code_hash)
);

CREATE INDEX IF NOT EXISTS idx_fallback_totp_recovery_unused
ON fallback_totp_recovery_codes(user_id)
WHERE used_at IS NULL;
