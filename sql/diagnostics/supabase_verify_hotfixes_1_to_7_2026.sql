-- ==========================================================
-- VERIFY HOTFIXES 1..7 (read-only checks)
-- ==========================================================
-- Supabase SQL Editor suele mostrar solo un result set a la vez.
-- Este formato devuelve una sola tabla para que puedas ver todas las comprobaciones juntas.
WITH checks AS (
    -- 1) guard_rate_limit
    SELECT
        1 AS check_order,
        'guard_rate_limit' AS check_name,
        EXISTS (
            SELECT 1
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_language l ON l.oid = p.prolang
            WHERE n.nspname = 'public'
              AND p.proname = 'guard_rate_limit'
              AND l.lanname = 'plpgsql'
        ) AS ok

    UNION ALL

    -- 2) DM reactions/forwarding
    SELECT
        2 AS check_order,
        'messages_columns' AS check_name,
        COUNT(*) FILTER (
            WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')
        ) = 4 AS ok
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'messages'

    UNION ALL

    -- 3) group/community receipts + polls
    SELECT
        3 AS check_order,
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
    )

    UNION ALL

    -- 4) group/community reactions + forwarding columns
    SELECT
        4 AS check_order,
        'group_messages_columns' AS check_name,
        COUNT(*) FILTER (
            WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')
        ) = 4 AS ok
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'group_messages'

    UNION ALL

    SELECT
        5 AS check_order,
        'community_messages_columns' AS check_name,
        COUNT(*) FILTER (
            WHERE column_name IN ('reactions', 'is_forwarded', 'forwarded_from_message_id', 'forwarded_from_user_id')
        ) = 4 AS ok
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'community_messages'

    UNION ALL

    -- 5) chat preferences mute/pin
    SELECT
        6 AS check_order,
        'chat_participants_mute_pin' AS check_name,
        COUNT(*) FILTER (WHERE column_name IN ('is_muted', 'is_pinned')) = 2 AS ok
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'chat_participants'

    UNION ALL

    -- 6) phase3 stickers/view_once/scheduler/folders
    SELECT
        7 AS check_order,
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
    )

    UNION ALL

    SELECT
        8 AS check_order,
        'secure_consume_view_once_message_fn' AS check_name,
        EXISTS (
            SELECT 1
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public'
              AND p.proname = 'secure_consume_view_once_message'
        ) AS ok

    UNION ALL

    -- 7) profile login aliases
    SELECT
        9 AS check_order,
        'profile_login_emails_table' AS check_name,
        EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = 'profile_login_emails'
        ) AS ok

    UNION ALL

    SELECT
        10 AS check_order,
        'resolve_login_email_fn' AS check_name,
        EXISTS (
            SELECT 1
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public'
              AND p.proname = 'resolve_login_email'
        ) AS ok

    UNION ALL

    SELECT
        11 AS check_order,
        'profile_login_global_uniqueness_rule' AS check_name,
        EXISTS (
            SELECT 1
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public'
              AND p.proname = 'enforce_profile_login_email_rules'
              AND pg_get_functiondef(p.oid) ILIKE '%EMAIL_ALREADY_IN_USE%'
        ) AS ok
)
SELECT
    check_name,
    ok
FROM checks
ORDER BY check_order;
