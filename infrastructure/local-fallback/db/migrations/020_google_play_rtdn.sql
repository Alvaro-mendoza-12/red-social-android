CREATE TABLE IF NOT EXISTS google_play_rtdn_events (
  message_id TEXT PRIMARY KEY,
  package_name TEXT NOT NULL,
  event_time TIMESTAMPTZ,
  notification_kind TEXT NOT NULL,
  notification_type INTEGER,
  purchase_token_hash TEXT,
  order_id_hash TEXT,
  product_id TEXT,
  status TEXT NOT NULL CHECK (status IN ('processing', 'processed', 'failed')),
  attempts INTEGER NOT NULL DEFAULT 1,
  result JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_error TEXT,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_google_play_rtdn_status_updated
  ON google_play_rtdn_events(status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_google_play_rtdn_purchase_hash
  ON google_play_rtdn_events(purchase_token_hash)
  WHERE purchase_token_hash IS NOT NULL;

COMMENT ON TABLE google_play_rtdn_events IS
  'Deduplicación y auditoría minimizada de Google Play RTDN. Nunca almacena tokens de compra ni el payload completo.';
