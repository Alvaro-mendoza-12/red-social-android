ALTER TABLE fallback_users
    ADD COLUMN IF NOT EXISTS password_changed_by_user_at TIMESTAMPTZ;
