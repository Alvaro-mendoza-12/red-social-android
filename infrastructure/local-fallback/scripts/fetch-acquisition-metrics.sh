#!/usr/bin/env bash
set -euo pipefail

# Read-only acquisition check. Run from ~/conectate-local-fallback on the
# mini PC. SINCE_DATE is intentionally explicit so campaign windows are
# reproducible and never depend on a hidden local timezone.
SINCE_DATE="${SINCE_DATE:-2026-07-01T00:00:00Z}"

sql=$(cat <<SQL
WITH candidates AS (
  SELECT id::text AS id, created_at
  FROM fallback_users
  WHERE created_at >= TIMESTAMPTZ '${SINCE_DATE}'
    AND email !~* '(example\\.(com|local|test)|test\\.invalid|novvin\\.invalid|(^|[._-])test([._-]|@)|(^|[._-])e2e([._-]|@)|(^|[._-])module([._-]|@)|(^|[._-])probe([._-]|@)|(^|[._-])codex([._-]|@)|(^|[._-])prod\\.([._-]|@))'
), action_users AS (
  SELECT user_id::text AS id FROM fallback_chat_participants WHERE user_id IS NOT NULL
  UNION SELECT sender_id::text FROM fallback_messages WHERE sender_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_posts WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_post_comments WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_post_reactions WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_videos WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_video_comments WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_video_likes WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_video_views WHERE user_id IS NOT NULL
  UNION SELECT follower_id::text FROM fallback_follows WHERE follower_id IS NOT NULL
  UNION SELECT following_id::text FROM fallback_follows WHERE following_id IS NOT NULL
  UNION SELECT sender_id::text FROM fallback_friends WHERE sender_id IS NOT NULL
  UNION SELECT receiver_id::text FROM fallback_friends WHERE receiver_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_shares WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_moments WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_voice_room_participants WHERE user_id IS NOT NULL
  UNION SELECT creator_id::text FROM fallback_voice_rooms WHERE creator_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_community_member_profiles WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_community_event_attendees WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_saved_posts WHERE user_id IS NOT NULL
  UNION SELECT user_id::text FROM fallback_saved_collections WHERE user_id IS NOT NULL
), candidates_with_activity AS (
  SELECT c.id
  FROM candidates c
  JOIN action_users a ON a.id = c.id
  GROUP BY c.id
)
SELECT
  (SELECT COUNT(*) FROM candidates) AS candidate_accounts,
  (SELECT COUNT(*) FROM candidates_with_activity) AS active_candidates,
  (SELECT COUNT(*) FROM candidates c WHERE c.created_at >= NOW() - INTERVAL '7 days') AS candidate_accounts_7d,
  (SELECT COUNT(*) FROM candidates c JOIN candidates_with_activity a USING (id)
   WHERE c.created_at >= NOW() - INTERVAL '7 days') AS active_candidates_7d,
  (SELECT COUNT(*) FROM fallback_audit_events
   WHERE event_name = 'auth_register'
     AND created_at >= NOW() - INTERVAL '7 days'
     AND COALESCE(metadata->>'email', '') !~* '(example\\.(com|local|test)|test\\.invalid|novvin\\.invalid|(^|[._-])test([._-]|@)|(^|[._-])e2e([._-]|@)|(^|[._-])module([._-]|@)|(^|[._-])probe([._-]|@)|(^|[._-])codex([._-]|@)|(^|[._-])prod\\.([._-]|@))'
    ) AS non_test_registrations_7d;
SQL
)

docker compose exec -T db psql -U conectate -d conectate_fallback -At -F '=' -c "$sql"
