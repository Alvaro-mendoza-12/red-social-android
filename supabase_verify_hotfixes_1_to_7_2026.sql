-- ==========================================================
-- VERIFY HOTFIXES 1..7 (read-only checks)
-- ==========================================================

-- 1) guard_rate_limit
SELECT
    'guard_rate_limit' AS check_name,
    EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        JOIN pg_language l ON l.oid = p.prolang
        WHERE n.nspname = 'public'
          AND p.proname = 'guard_rate_limit'
          AND l.lanname = 'plpgsql'
    ) AS ok;

-- 2) DM reactions/forwarding
SELECT
    'messages_columns' AS check_name,
    COUNT(*) FILTER (WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')) = 4 AS ok
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'messages';

-- 3) group/community receipts + polls
SELECT
    'polls_and_receipts_tables' AS check_name,
    COUNT(*) = 5 AS ok
FROM (
    SELECT 'message_polls' t UNION ALL
    SELECT 'message_poll_options' UNION ALL
    SELECT 'message_poll_votes' UNION ALL
    SELECT 'group_message_receipts' UNION ALL
    SELECT 'community_message_receipts'
) q
WHERE EXISTS (
    SELECT 1
    FROM information_schema.tables i
    WHERE i.table_schema = 'public'
      AND i.table_name = q.t
);

-- 4) group/community reactions + forwarding columns
SELECT
    'group_messages_columns' AS check_name,
    COUNT(*) FILTER (WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')) = 4 AS ok
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'group_messages';

SELECT
    'community_messages_columns' AS check_name,
    COUNT(*) FILTER (WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')) = 4 AS ok
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'community_messages';

-- 5) chat preferences mute/pin
SELECT
    'chat_participants_mute_pin' AS check_name,
    COUNT(*) FILTER (WHERE column_name IN ('is_muted', 'is_pinned')) = 2 AS ok
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'chat_participants';

-- 6) phase3 stickers/view_once/scheduler/folders
SELECT
    'phase3_tables' AS check_name,
    COUNT(*) = 10 AS ok
FROM (
    SELECT 'sticker_catalog_items' t UNION ALL
    SELECT 'sticker_packs' UNION ALL
    SELECT 'sticker_pack_items' UNION ALL
    SELECT 'user_sticker_favorites' UNION ALL
    SELECT 'message_view_once_consumptions' UNION ALL
    SELECT 'scheduled_message_jobs' UNION ALL
    SELECT 'chat_translation_settings' UNION ALL
    SELECT 'chat_folders' UNION ALL
    SELECT 'chat_folder_chats' UNION ALL
    SELECT 'chat_summary_cache'
) q
WHERE EXISTS (
    SELECT 1
    FROM information_schema.tables i
    WHERE i.table_schema = 'public'
      AND i.table_name = q.t
);

SELECT
    'secure_consume_view_once_message_fn' AS check_name,
    EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.proname = 'secure_consume_view_once_message'
    ) AS ok;

-- 7) profile login aliases
SELECT
    'profile_login_emails_table' AS check_name,
    EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'profile_login_emails'
    ) AS ok;

SELECT
    'resolve_login_email_fn' AS check_name,
    EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.proname = 'resolve_login_email'
    ) AS ok;

SELECT
    'profile_login_global_uniqueness_rule' AS check_name,
    EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.proname = 'enforce_profile_login_email_rules'
          AND pg_get_functiondef(p.oid) ILIKE '%EMAIL_ALREADY_IN_USE%'
    ) AS ok;
