-- Run as the bootstrap administrator after 10-bootstrap-runtime-role.sql.
-- Explicit runtime read contract; no blanket/default grants or imported writes.
-- Re-run after administrative CSV imports replace any allowed table.
BEGIN;
DO $operation$
DECLARE
  table_name TEXT;
  relation_oid OID;
  kind "char";
  allowed_tables CONSTANT TEXT[] := ARRAY[
    'active_challenges',
    'ad_campaigns',
    'ad_impressions',
    'app_telemetry_events',
    'badges',
    'billing_purchases',
    'blocked_users',
    'challenge_submissions',
    'challenge_votes',
    'challenges_master',
    'chat_folder_chats',
    'chat_folders',
    'chat_participants',
    'chat_summary_cache',
    'chat_translation_settings',
    'chats',
    'comments',
    'communities',
    'community_members',
    'community_message_receipts',
    'community_messages',
    'creator_earnings',
    'creator_payout_accounts',
    'creator_program_applications',
    'fcm_tokens',
    'follows',
    'friends',
    'gift_coin_allocations',
    'gift_transactions',
    'group_members',
    'group_message_receipts',
    'group_messages',
    'groups',
    'live_comments',
    'live_streams',
    'message_poll_options',
    'message_poll_votes',
    'message_polls',
    'messages',
    'moderation_events',
    'moment_highlight_items',
    'moment_highlights',
    'moment_views',
    'moments',
    'nicknames',
    'notifications',
    'post_comments',
    'post_likes',
    'post_reactions',
    'posts',
    'profile_login_emails',
    'profile_views',
    'profiles',
    'reports',
    'scheduled_message_jobs',
    'shares',
    'sticker_pack_items',
    'sticker_packs',
    'subscription_plans',
    'user_badges',
    'user_sticker_favorites',
    'user_subscriptions',
    'users',
    'video_comments',
    'video_likes',
    'video_reports',
    'videos',
    'virtual_gifts',
    'voice_access_requests',
    'voice_room_participants',
    'voice_rooms',
    'wallet_coin_lots',
    'wallet_transactions',
    'wallets'
  ];
BEGIN
  IF current_database() <> 'conectate_fallback' THEN
    RAISE EXCEPTION 'Imported read bootstrap requires the dedicated conectate_fallback database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_api') THEN
    RAISE EXCEPTION 'Install the restricted novvin_api runtime role first';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_api' AND
      (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Existing novvin_api role has unsafe capabilities';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='supabase_import') THEN
    RETURN; -- Imports are optional. Re-run this operation after the first import.
  END IF;
  IF has_schema_privilege('novvin_api','supabase_import','CREATE') THEN
    RAISE EXCEPTION 'novvin_api must not create objects in supabase_import';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='supabase_import' AND c.relkind IN ('r','p','v','m','f')
        AND (pg_has_role('novvin_api',c.relowner,'USAGE') OR
          has_table_privilege('novvin_api',c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))) THEN
    RAISE EXCEPTION 'novvin_api must not own or write imported relations';
  END IF;
  GRANT USAGE ON SCHEMA supabase_import TO novvin_api;
  FOREACH table_name IN ARRAY allowed_tables LOOP
    relation_oid := to_regclass(format('supabase_import.%I',table_name));
    IF relation_oid IS NULL THEN CONTINUE; END IF;
    SELECT relkind INTO kind FROM pg_class WHERE oid=relation_oid;
    IF kind NOT IN ('r','p') THEN
      RAISE EXCEPTION 'Imported runtime relation % must be a table, not a view/foreign relation',table_name;
    END IF;
    EXECUTE format('GRANT SELECT ON TABLE supabase_import.%I TO novvin_api',table_name);
  END LOOP;
END
$operation$;
COMMIT;
