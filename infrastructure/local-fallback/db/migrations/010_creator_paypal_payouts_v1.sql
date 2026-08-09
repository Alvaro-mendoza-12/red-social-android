CREATE TABLE IF NOT EXISTS fallback_creator_payout_accounts (
  creator_user_id TEXT PRIMARY KEY,
  provider TEXT NOT NULL DEFAULT 'paypal' CHECK (provider = 'paypal'),
  recipient_email_ciphertext TEXT NOT NULL,
  recipient_email_hash TEXT NOT NULL UNIQUE,
  recipient_email_masked TEXT NOT NULL,
  currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','DISABLED')),
  verified_by TEXT,
  verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_creator_payout_requests (
  id UUID PRIMARY KEY,
  creator_user_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','PROCESSING','PAID','FAILED','REJECTED','CANCELLED')),
  recipient_email_ciphertext TEXT NOT NULL,
  recipient_email_hash TEXT NOT NULL,
  recipient_email_masked TEXT NOT NULL,
  paypal_sender_batch_id TEXT NOT NULL UNIQUE,
  paypal_payout_batch_id TEXT UNIQUE,
  paypal_payout_item_id TEXT UNIQUE,
  failure_code TEXT,
  approved_by TEXT,
  approved_at TIMESTAMPTZ,
  processing_at TIMESTAMPTZ,
  paid_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (creator_user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_creator_payout_requests_worker
  ON fallback_creator_payout_requests(status, updated_at);

CREATE TABLE IF NOT EXISTS fallback_creator_payout_allocations (
  request_id UUID NOT NULL REFERENCES fallback_creator_payout_requests(id) ON DELETE CASCADE,
  earning_id TEXT NOT NULL,
  allocated_minor BIGINT NOT NULL CHECK (allocated_minor > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (request_id, earning_id)
);

CREATE INDEX IF NOT EXISTS idx_creator_payout_allocations_earning
  ON fallback_creator_payout_allocations(earning_id);

CREATE TABLE IF NOT EXISTS fallback_creator_payout_ledger (
  id UUID PRIMARY KEY,
  creator_user_id TEXT NOT NULL,
  request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
  event_key TEXT NOT NULL UNIQUE,
  event_type TEXT NOT NULL,
  amount_minor BIGINT NOT NULL DEFAULT 0,
  currency TEXT NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_paypal_webhook_events (
  event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  payout_request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
  result_status TEXT,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS fallback_creator_payout_audit (
  id UUID PRIMARY KEY,
  actor_user_id TEXT,
  creator_user_id TEXT NOT NULL,
  request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
  event_type TEXT NOT NULL,
  reason_code TEXT,
  request_id_header TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE fallback_creator_payout_requests IS
  'Retiros de creadores aprobados manualmente. Nunca representa monedas compradas por el espectador.';
COMMENT ON COLUMN fallback_creator_payout_accounts.recipient_email_ciphertext IS
  'Correo PayPal cifrado por el backend; nunca debe exponerse a Android, logs ni repositorio.';
