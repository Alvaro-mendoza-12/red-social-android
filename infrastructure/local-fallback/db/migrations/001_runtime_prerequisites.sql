-- New migration: run after db/init/001_schema.sql and before migrations/002_*.sql.
-- These definitions match ensureLocalDataTables runtime DDL. Existing historical
-- migrations remain unchanged. No API startup or human rows are required.
BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Source data-routes.js:5403
CREATE TABLE IF NOT EXISTS fallback_chats (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      participants TEXT[] NOT NULL DEFAULT '{}'::text[],
      participant_key TEXT UNIQUE,
      is_group BOOLEAN NOT NULL DEFAULT FALSE,
      group_name TEXT,
      last_message TEXT,
      last_message_at TIMESTAMPTZ,
      last_message_sender_id TEXT,
      last_message_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS participants TEXT[] NOT NULL DEFAULT '{}'::text[];
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS participant_key TEXT UNIQUE;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS is_group BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS group_name TEXT;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message TEXT;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_sender_id TEXT;
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_id TEXT;

-- Source data-routes.js:5512
CREATE TABLE IF NOT EXISTS fallback_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      chat_id TEXT NOT NULL,
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      thumbnail_url TEXT,
      media_size BIGINT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'sent',
      reply_to_id TEXT,
      poll_id TEXT,
      delivered_at TIMESTAMPTZ,
      read_at TIMESTAMPTZ,
      reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
      deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb,
      deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE,
      is_edited BOOLEAN NOT NULL DEFAULT FALSE,
      is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
      forwarded_from_message_id TEXT,
      forwarded_from_user_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS media_size BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS poll_id TEXT;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS forwarded_from_message_id TEXT;
ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS forwarded_from_user_id TEXT;

-- Source data-routes.js:5558
CREATE TABLE IF NOT EXISTS fallback_posts (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_profile_image TEXT,
      content TEXT NOT NULL DEFAULT '',
      media_url TEXT,
      media_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
      media_type TEXT,
      visibility TEXT NOT NULL DEFAULT 'public',
      is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
      remix_allowed BOOLEAN NOT NULL DEFAULT FALSE,
      remix_audience TEXT NOT NULL DEFAULT 'public',
      remix_revoked_at TIMESTAMPTZ,
      remix_source_post_id TEXT,
      remix_root_post_id TEXT,
      remix_source_user_id TEXT,
      remix_source_user_name TEXT,
      remix_depth INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public';
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS is_sensitive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_audience TEXT NOT NULL DEFAULT 'public';
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_revoked_at TIMESTAMPTZ;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_post_id TEXT;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_root_post_id TEXT;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_user_id TEXT;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_user_name TEXT;
ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_depth INTEGER NOT NULL DEFAULT 0;

-- Source data-routes.js:6312
CREATE TABLE IF NOT EXISTS fallback_profile_overrides (
      user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
      profile JSONB NOT NULL DEFAULT '{}'::jsonb,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

-- Source data-routes.js:6342
CREATE TABLE IF NOT EXISTS fallback_videos (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_profile_image TEXT,
      title TEXT NOT NULL DEFAULT '',
      description TEXT NOT NULL DEFAULT '',
      video_url TEXT NOT NULL,
      cover_url TEXT,
      thumbnail_url TEXT,
      sound_id TEXT,
      sound_source_video_id TEXT,
      sound_title TEXT,
      sound_artist TEXT,
      sound_url TEXT,
      duration_seconds INT NOT NULL DEFAULT 0,
      trim_start_seconds INT NOT NULL DEFAULT 0,
      trim_end_seconds INT NOT NULL DEFAULT 0,
      views INT NOT NULL DEFAULT 0,
      like_count INT NOT NULL DEFAULT 0,
      comment_count INT NOT NULL DEFAULT 0,
      share_count INT NOT NULL DEFAULT 0,
      is_public BOOLEAN NOT NULL DEFAULT TRUE,
      is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
      tags JSONB NOT NULL DEFAULT '[]'::jsonb,
      chapters JSONB NOT NULL DEFAULT '[]'::jsonb,
      allow_clip_sharing BOOLEAN NOT NULL DEFAULT TRUE,
      client_publish_id UUID,
      client_requested_sound_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT '';
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_name TEXT NOT NULL DEFAULT 'Usuario';
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_profile_image TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '';
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '';
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS video_url TEXT NOT NULL DEFAULT '';
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS cover_url TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS sound_id TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS sound_source_video_id TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS sound_title TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS sound_artist TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS sound_url TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS duration_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS trim_start_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS trim_end_seconds INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS views INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS like_count INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS comment_count INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS share_count INT NOT NULL DEFAULT 0;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS is_sensitive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS chapters JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS allow_clip_sharing BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS client_publish_id UUID;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS client_requested_sound_id TEXT;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Source data-routes.js:6578
CREATE TABLE IF NOT EXISTS fallback_video_comment_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      comment_id TEXT NOT NULL,
      video_id TEXT NOT NULL DEFAULT '',
      reporter_id TEXT NOT NULL,
      reason TEXT NOT NULL,
      details TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL DEFAULT 'pending',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (comment_id, reporter_id)
    );

-- Required by 034_video_comment_reports_viewer and current runtime queries.
ALTER TABLE fallback_video_comment_reports ADD COLUMN IF NOT EXISTS video_id TEXT NOT NULL DEFAULT '';

COMMIT;
