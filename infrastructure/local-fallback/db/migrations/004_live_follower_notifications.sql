-- Follower Live notifications are durable work items. The unique delivery key
-- prevents a reconnecting host from creating duplicate activity records.
CREATE TABLE IF NOT EXISTS fallback_live_follower_notification_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
    recipient_id TEXT NOT NULL,
    host_user_id TEXT NOT NULL,
    host_name TEXT NOT NULL DEFAULT 'Usuario',
    host_avatar_url TEXT,
    notification_type TEXT NOT NULL DEFAULT 'live_started'
        CHECK (notification_type IN ('live_started')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    notification_id UUID REFERENCES fallback_notifications(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'delivered', 'failed', 'suppressed')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_live_follower_notification_outbox_unique
    ON fallback_live_follower_notification_outbox(
        live_id,
        lower(recipient_id),
        notification_type
    );

CREATE INDEX IF NOT EXISTS idx_live_follower_notification_outbox_due
    ON fallback_live_follower_notification_outbox(status, available_at, created_at)
    WHERE status IN ('queued', 'failed', 'processing');

-- Existing historical notifications do not carry delivery_key, so preserving
-- them is safe while new Live starts are guaranteed idempotent.
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_notifications_live_started_delivery
    ON fallback_notifications(
        lower(recipient_id),
        lower(target_id),
        lower(type),
        (extra_data->>'delivery_key')
    )
    WHERE lower(type) = 'live_started'
      AND extra_data ? 'delivery_key';
