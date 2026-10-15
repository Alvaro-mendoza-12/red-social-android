-- Administrator-only installation after operations 10 and 15, dedicated DB.
-- No imported table DML/ownership is granted to the API login. Functions retain
-- application endpoint authorization; they do not authenticate end users.
BEGIN;
DO $install$
DECLARE rel record; table_name text;
BEGIN
  IF current_database() <> 'conectate_fallback' THEN
    RAISE EXCEPTION 'Imported mutation contract requires conectate_fallback';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_api') THEN
    RAISE EXCEPTION 'Bootstrap novvin_api first';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_import_operator') THEN
    CREATE ROLE novvin_import_operator NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('novvin_api','novvin_import_operator')
      AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) OR
      EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_import_operator' AND rolcanlogin) OR
      EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='novvin_import_operator')) OR
      pg_has_role('novvin_api','novvin_import_operator','MEMBER') THEN
    RAISE EXCEPTION 'Unsafe imported mutation role or membership';
  END IF;
  IF to_regnamespace('novvin_import_mutation') IS NOT NULL AND
     (has_schema_privilege('novvin_api','novvin_import_mutation','CREATE') OR
      has_schema_privilege('novvin_import_operator','novvin_import_mutation','CREATE')) THEN
    RAISE EXCEPTION 'Runtime must not create mutation capability objects';
  END IF;
END
$install$;

CREATE SCHEMA IF NOT EXISTS novvin_import_mutation;
REVOKE ALL ON SCHEMA novvin_import_mutation FROM PUBLIC, novvin_api, novvin_import_operator;
GRANT USAGE ON SCHEMA novvin_import_mutation TO novvin_api, novvin_import_operator;

CREATE OR REPLACE FUNCTION novvin_import_mutation.allowed_tables()
RETURNS text[] LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $function$
  SELECT ARRAY[
    'active_challenges','ad_campaigns','ad_impressions','app_telemetry_events','badges',
    'billing_purchases','blocked_users','challenge_submissions','challenge_submissions_with_votes','challenge_votes','challenges_master',
    'chat_folder_chats','chat_folders','chat_participants','chat_summary_cache','chat_translation_settings',
    'chats','comments','communities','community_members','community_message_receipts','community_messages',
    'creator_earnings','creator_payout_accounts','creator_program_applications','fcm_tokens','follows','friends',
    'gift_coin_allocations','gift_transactions','group_members','group_message_receipts','group_messages','groups',
    'live_comments','live_streams','media_registry','message_poll_options','message_poll_votes','message_polls','messages',
    'moderation_events','moment_highlight_items','moment_highlights','moment_views','moments','nicknames',
    'security_audit_events','security_rate_limit_state','security_rate_limit_windows',
    'notifications','post_comments','post_likes','post_reactions','posts','profile_login_emails','profile_views',
    'profiles','reports','scheduled_message_jobs','shares','sticker_pack_items','sticker_packs','subscription_plans',
    'user_badges','user_sticker_favorites','user_subscriptions','users','video_comments','video_likes',
    'video_reports','videos','virtual_gifts','voice_access_requests','voice_room_participants','voice_rooms',
    'wallet_coin_lots','wallet_transactions','wallets'
  ]::text[]
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.normalize_values(values_in text[])
RETURNS text[] LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $function$
DECLARE normalized text[];
BEGIN
  IF values_in IS NULL OR cardinality(values_in) NOT BETWEEN 1 AND 10000 OR
     EXISTS(SELECT 1 FROM unnest(values_in) item WHERE item IS NULL OR length(btrim(item)) NOT BETWEEN 1 AND 256) THEN
    RAISE EXCEPTION 'Invalid bounded imported identity set' USING ERRCODE='22023';
  END IF;
  SELECT array_agg(DISTINCT lower(btrim(item))) INTO normalized FROM unnest(values_in) item;
  RETURN normalized;
END
$function$;

-- The import stores lists as JSON text, SQL array text or native JSON/arrays.
-- Every accepted representation compares complete elements; wildcard text is
-- never interpreted as a LIKE pattern or SQL expression.
CREATE OR REPLACE FUNCTION novvin_import_mutation.array_matches(value_in jsonb, values_in text[])
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $function$
DECLARE decoded jsonb; legacy text[]; text_value text;
BEGIN
  IF value_in IS NULL OR value_in='null'::jsonb THEN RETURN false; END IF;
  decoded := value_in;
  IF jsonb_typeof(decoded)='string' THEN
    text_value := decoded #>> '{}';
    BEGIN decoded := text_value::jsonb;
    EXCEPTION WHEN invalid_text_representation THEN
      BEGIN legacy := text_value::text[];
      EXCEPTION WHEN invalid_text_representation THEN RETURN lower(text_value)=ANY(values_in);
      END;
      RETURN EXISTS(SELECT 1 FROM unnest(legacy) item WHERE lower(btrim(item))=ANY(values_in));
    END;
  END IF;
  IF jsonb_typeof(decoded) <> 'array' THEN RETURN false; END IF;
  RETURN EXISTS(SELECT 1 FROM jsonb_array_elements(decoded) item
    WHERE jsonb_typeof(item)='string' AND lower(btrim(item #>> '{}'))=ANY(values_in));
END
$function$;

-- The five reviewed legacy CSV snapshots (challenge vote summaries, media
-- registry and security audit/rate-limit exports) are included only in the
-- bounded identity/related purge. This does not grant API SELECT or writes to
-- these snapshots and does not alter active public security records.
-- Refuse unknown identity-bearing tables and views/foreign relations. This is
-- checked at installation and again before purge, including later CSV imports.
CREATE OR REPLACE FUNCTION novvin_import_mutation.assert_inventory()
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $function$
DECLARE rel record;
BEGIN
  FOR rel IN SELECT DISTINCT c.relname,c.relkind
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
    WHERE n.nspname='supabase_import' AND c.relkind IN ('r','p','v','m','f')
      AND a.attname=ANY(ARRAY['id','actor_id','author_id','blocked_id','blocked_user_id','blocker_id',
        'creator_id','follower_id','following_id','forwarded_from_user_id','from_user_id','host_user_id',
        'member_id','owner_id','profile_id','receiver_id','recipient_id','reported_user_id','reporter_id',
        'requester_id','sender_id','session_user_id','target_user_id','user_a','user_b','user_id','userid',
        'voter_id','attempted_recipient_ids','excluded_ids','member_ids','participant_ids','participants','user_ids',
        'active_challenge_id','challenge_id','chat_id','comment_id','community_id','group_id','live_id','message_id',
        'moment_id','post_id','room_id','submission_id','video_id','voice_room_id'])
  LOOP
    -- Reviewed operational CSV snapshots have no dedicated user identity.
    -- Preserve them without granting the import operator SELECT/DELETE. Any
    -- new identity-bearing column invalidates this narrow exception.
    IF rel.relname IN ('observability_alert_events','observability_metric_thresholds') AND rel.relkind='r' THEN
      IF EXISTS(SELECT 1 FROM pg_attribute a
        WHERE a.attrelid=to_regclass(format('supabase_import.%I',rel.relname))
          AND a.attnum>0 AND NOT a.attisdropped AND NOT a.attname=ANY(
            CASE WHEN rel.relname='observability_alert_events'
              THEN ARRAY['__source_file','__imported_at','id','metric_name','severity','event_count','window_seconds','metadata','created_at']
              ELSE ARRAY['__source_file','__imported_at','metric_name','warning_count','critical_count','window_seconds','cooldown_seconds','enabled','updated_at'] END)) THEN
        RAISE EXCEPTION 'Operational import schema outside reviewed contract: %',rel.relname USING ERRCODE='42501';
      END IF;
      CONTINUE;
    END IF;
    IF NOT (rel.relname=ANY(novvin_import_mutation.allowed_tables())) OR rel.relkind NOT IN ('r','p') THEN
      RAISE EXCEPTION 'Imported mutation relation outside reviewed contract: %',rel.relname USING ERRCODE='42501';
    END IF;
  END LOOP;
END
$function$;

-- Every entry point checks the complete privilege chain before any DML. An
-- administrator must install operation 22 before enabling imported mutations.
-- Runtime-owned public functions must never run with the import operator's DML.
CREATE OR REPLACE FUNCTION novvin_import_mutation.assert_trigger_boundary()
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $function$
DECLARE role_id oid; relation record;
BEGIN
  SELECT oid INTO role_id FROM pg_roles WHERE rolname='novvin_storage_binding_writer'
    AND NOT (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls);
  IF role_id IS NULL OR EXISTS(SELECT 1 FROM pg_auth_members WHERE roleid=role_id OR member=role_id)
    OR to_regnamespace('novvin_storage_guard') IS NULL
    OR has_schema_privilege('novvin_api','novvin_storage_guard','CREATE')
    OR has_schema_privilege('novvin_import_operator','novvin_storage_guard','CREATE')
    OR has_schema_privilege(role_id,'novvin_storage_guard','CREATE') THEN
    RAISE EXCEPTION 'Protected import trigger bootstrap required' USING ERRCODE='42501';
  END IF;
  IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='novvin_storage_guard' AND (p.proowner<>role_id
        OR NOT (COALESCE(p.proconfig,'{}'::text[]) @> ARRAY['search_path=pg_catalog, novvin_storage_guard']
          OR COALESCE(p.proconfig,'{}'::text[]) @> ARRAY['search_path=pg_catalog,novvin_storage_guard'])))
    OR (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='novvin_storage_guard')<>5 THEN
    RAISE EXCEPTION 'Protected import trigger function contract unsafe' USING ERRCODE='42501';
  END IF;
  FOR relation IN SELECT c.oid,c.relname,c.relowner,c.relkind,c.relrowsecurity,c.relispartition
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='supabase_import' AND c.relkind IN ('r','p') LOOP
    IF relation.relkind<>'r' OR relation.relrowsecurity OR relation.relispartition
      OR has_table_privilege(role_id,relation.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
      OR relation.relowner=role_id OR pg_has_role('novvin_api',relation.relowner,'USAGE')
      OR EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_type ty ON ty.oid=a.atttypid
        JOIN pg_namespace ns ON ns.oid=ty.typnamespace
        WHERE a.attrelid=relation.oid AND a.attnum>0 AND NOT a.attisdropped AND ns.nspname<>'pg_catalog')
      OR EXISTS(SELECT 1 FROM pg_rewrite WHERE ev_class=relation.oid)
      OR EXISTS(SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid
        JOIN pg_namespace n ON n.oid=p.pronamespace WHERE t.tgrelid=relation.oid
        AND NOT t.tgisinternal AND t.tgenabled<>'D' AND
          (n.nspname<>'novvin_storage_guard' OR p.proowner<>role_id OR NOT p.prosecdef
           OR t.tgtype<>29 OR p.pronargs<>0 OR p.proname NOT IN
             ('novvin_record_private_media_history','novvin_record_storage_content_bindings')))
      OR EXISTS(SELECT 1 FROM pg_constraint ct JOIN pg_depend dep
        ON dep.classid='pg_constraint'::regclass AND dep.objid=ct.oid AND dep.refclassid='pg_proc'::regclass
        JOIN pg_proc p ON p.oid=dep.refobjid JOIN pg_namespace ns ON ns.oid=p.pronamespace
        WHERE ct.conrelid=relation.oid AND ns.nspname<>'pg_catalog')
      OR EXISTS(SELECT 1 FROM pg_index ix JOIN pg_depend dep
        ON dep.classid='pg_class'::regclass AND dep.objid=ix.indexrelid AND dep.refclassid='pg_proc'::regclass
        JOIN pg_proc p ON p.oid=dep.refobjid JOIN pg_namespace ns ON ns.oid=p.pronamespace
        WHERE ix.indrelid=relation.oid AND ns.nspname<>'pg_catalog')
      OR EXISTS(SELECT 1 FROM pg_attrdef def JOIN pg_depend dep
        ON dep.classid='pg_attrdef'::regclass AND dep.objid=def.oid AND dep.refclassid='pg_proc'::regclass
        JOIN pg_proc p ON p.oid=dep.refobjid JOIN pg_namespace ns ON ns.oid=p.pronamespace
        WHERE def.adrelid=relation.oid AND ns.nspname<>'pg_catalog') THEN
      RAISE EXCEPTION 'Unsafe imported trigger, relation or expression: %',relation.relname USING ERRCODE='42501';
    END IF;
  END LOOP;
END
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.patch_row(table_in text,id_in text,patch_in jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE normalized_id text; relation_id oid; allowed_keys text[]; key text; assignments text[]:=ARRAY[]::text[];
        result jsonb; affected integer; has_updated boolean;
BEGIN
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  normalized_id := (novvin_import_mutation.normalize_values(ARRAY[id_in]))[1];
  IF table_in NOT IN ('posts','post_comments') OR patch_in IS NULL OR jsonb_typeof(patch_in)<>'object' OR patch_in='{}'::jsonb THEN
    RAISE EXCEPTION 'Invalid imported patch contract' USING ERRCODE='22023';
  END IF;
  allowed_keys := CASE WHEN table_in='posts' THEN ARRAY['content','visibility'] ELSE ARRAY['content'] END;
  relation_id := to_regclass(format('supabase_import.%I',table_in));
  IF relation_id IS NULL THEN RETURN NULL; END IF;
  FOR key IN SELECT jsonb_object_keys(patch_in) LOOP
    IF NOT key=ANY(allowed_keys) OR jsonb_typeof(patch_in->key)<>'string' OR
       NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=relation_id AND attname=key AND attnum>0 AND NOT attisdropped) THEN
      RAISE EXCEPTION 'Unsupported imported patch field' USING ERRCODE='22023';
    END IF;
    IF key='content' AND (length(patch_in->>key)>CASE WHEN table_in='posts' THEN 5000 ELSE 4000 END OR
        (table_in='post_comments' AND btrim(patch_in->>key)='')) THEN
      RAISE EXCEPTION 'Invalid imported content length' USING ERRCODE='22023';
    END IF;
    IF key='visibility' AND patch_in->>key NOT IN ('public','followers','private') THEN
      RAISE EXCEPTION 'Invalid imported visibility' USING ERRCODE='22023';
    END IF;
    assignments := array_append(assignments,format('%I = $2->>%L',key,key));
  END LOOP;
  SELECT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=relation_id AND attname='updated_at' AND attnum>0 AND NOT attisdropped) INTO has_updated;
  IF has_updated THEN assignments:=array_append(assignments,'updated_at = NOW()'); END IF;
  EXECUTE format('UPDATE supabase_import.%I AS target SET %s WHERE lower(target.id::text)=$1 RETURNING to_jsonb(target)',table_in,array_to_string(assignments,', '))
    INTO result USING normalized_id,patch_in;
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected>1 THEN RAISE EXCEPTION 'Ambiguous imported identity' USING ERRCODE='21000'; END IF;
  RETURN result;
END
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.delete_row(table_in text,id_in text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE normalized_id text; affected integer;
BEGIN
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  normalized_id := (novvin_import_mutation.normalize_values(ARRAY[id_in]))[1];
  IF table_in NOT IN ('post_comments','video_comments') THEN
    RAISE EXCEPTION 'Imported row deletion outside reviewed contract' USING ERRCODE='22023';
  END IF;
  IF to_regclass(format('supabase_import.%I',table_in)) IS NULL THEN RETURN 0; END IF;
  EXECUTE format('DELETE FROM supabase_import.%I WHERE lower(id::text)=$1',table_in) USING normalized_id;
  GET DIAGNOSTICS affected=ROW_COUNT;
  IF affected>1 THEN RAISE EXCEPTION 'Ambiguous imported identity' USING ERRCODE='21000'; END IF;
  RETURN affected;
END
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.delete_post(id_in text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE normalized_id text; table_name text; affected integer; existing_id text; relation_id oid;
BEGIN
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  normalized_id := (novvin_import_mutation.normalize_values(ARRAY[id_in]))[1];
  IF to_regclass('supabase_import.posts') IS NULL THEN RETURN 0; END IF;
  SELECT id::text INTO existing_id FROM supabase_import.posts WHERE lower(id::text)=normalized_id FOR UPDATE;
  IF existing_id IS NULL THEN RETURN 0; END IF;
  FOREACH table_name IN ARRAY ARRAY['post_comments','post_likes','post_reactions','shares'] LOOP
    relation_id := to_regclass(format('supabase_import.%I',table_name));
    IF relation_id IS NOT NULL AND EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=relation_id AND attname='post_id' AND attnum>0 AND NOT attisdropped) THEN
      EXECUTE format('DELETE FROM supabase_import.%I WHERE lower(post_id::text)=$1',table_name) USING normalized_id;
    END IF;
  END LOOP;
  DELETE FROM supabase_import.posts WHERE lower(id::text)=normalized_id;
  GET DIAGNOSTICS affected=ROW_COUNT;
  IF affected>1 THEN RAISE EXCEPTION 'Ambiguous imported identity' USING ERRCODE='21000'; END IF;
  RETURN affected;
END
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.delete_poll_votes(poll_in text,users_in text[],all_in boolean,option_in text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE selected_poll_id text; selected_option_id text; users_ids text[]; affected integer;
BEGIN
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  selected_poll_id := (novvin_import_mutation.normalize_values(ARRAY[poll_in]))[1];
  selected_option_id := (novvin_import_mutation.normalize_values(ARRAY[option_in]))[1];
  users_ids := novvin_import_mutation.normalize_values(users_in);
  IF cardinality(users_ids)>8 OR all_in IS NULL THEN RAISE EXCEPTION 'Invalid imported vote scope' USING ERRCODE='22023'; END IF;
  IF to_regclass('supabase_import.message_poll_votes') IS NULL THEN RETURN 0; END IF;
  DELETE FROM supabase_import.message_poll_votes AS vote
    WHERE lower(to_jsonb(vote)->>'poll_id')=selected_poll_id AND lower(to_jsonb(vote)->>'user_id')=ANY(users_ids)
      AND (all_in OR lower(to_jsonb(vote)->>'option_id')=selected_option_id);
  GET DIAGNOSTICS affected=ROW_COUNT;
  RETURN affected;
END
$function$;

CREATE OR REPLACE FUNCTION novvin_import_mutation.purge_rows(values_in text[],mode_in text)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE values_ids text[]; table_name text; relation_id oid; exact_fields text[]; array_fields text[];
        identity_fields CONSTANT text[] := ARRAY['actor_id','author_id','blocked_id','blocked_user_id','blocker_id',
          'creator_id','follower_id','following_id','forwarded_from_user_id','from_user_id','host_user_id','member_id',
          'owner_id','profile_id','receiver_id','recipient_id','reported_user_id','reporter_id','requester_id','sender_id',
          'session_user_id','target_user_id','user_a','user_b','user_id','userid','voter_id'];
        related_fields CONSTANT text[] := ARRAY['active_challenge_id','challenge_id','chat_id','comment_id','community_id',
          'group_id','id','live_id','message_id','moment_id','post_id','room_id','submission_id','video_id','voice_room_id'];
        list_fields CONSTANT text[] := ARRAY['attempted_recipient_ids','excluded_ids','member_ids','participant_ids','participants','user_ids'];
        affected integer; total integer:=0;
BEGIN
  values_ids := novvin_import_mutation.normalize_values(values_in);
  IF mode_in NOT IN ('identity','related') OR mode_in IS NULL THEN RAISE EXCEPTION 'Invalid imported purge mode' USING ERRCODE='22023'; END IF;
  PERFORM novvin_import_mutation.assert_inventory();
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  FOREACH table_name IN ARRAY novvin_import_mutation.allowed_tables() LOOP
    relation_id := to_regclass(format('supabase_import.%I',table_name));
    IF relation_id IS NULL THEN CONTINUE; END IF;
    SELECT array_agg(attname::text) FILTER(WHERE attname=ANY(identity_fields) OR
      (mode_in='related' AND attname=ANY(related_fields)) OR
      (mode_in='identity' AND table_name IN ('profiles','users') AND attname='id')),
      array_agg(attname::text) FILTER(WHERE attname=ANY(list_fields))
      INTO exact_fields,array_fields FROM pg_attribute WHERE attrelid=relation_id AND attnum>0 AND NOT attisdropped;
    IF exact_fields IS NULL AND array_fields IS NULL THEN CONTINUE; END IF;
    EXECUTE format('DELETE FROM supabase_import.%I AS target WHERE EXISTS(
      SELECT 1 FROM jsonb_each(to_jsonb(target)) field WHERE
        (field.key=ANY($2::text[]) AND lower(COALESCE(field.value #>> ''{}'',''''))=ANY($1::text[])) OR
        (field.key=ANY($3::text[]) AND novvin_import_mutation.array_matches(field.value,$1::text[])))',table_name)
      USING values_ids,exact_fields,array_fields;
    GET DIAGNOSTICS affected=ROW_COUNT;
    total:=total+affected;
  END LOOP;
  RETURN total;
END
$function$;

DO $privileges$
DECLARE table_name text; relation_id oid; key text; rel record;
BEGIN
  PERFORM novvin_import_mutation.assert_inventory();
  PERFORM novvin_import_mutation.assert_trigger_boundary();
  IF to_regnamespace('supabase_import') IS NOT NULL THEN
    GRANT USAGE ON SCHEMA supabase_import TO novvin_import_operator;
    IF has_schema_privilege('novvin_api','supabase_import','CREATE') OR
       has_schema_privilege('novvin_import_operator','supabase_import','CREATE') THEN
      RAISE EXCEPTION 'Imported schema CREATE capability forbidden';
    END IF;
    FOREACH table_name IN ARRAY novvin_import_mutation.allowed_tables() LOOP
      relation_id:=to_regclass(format('supabase_import.%I',table_name));
      IF relation_id IS NULL THEN CONTINUE; END IF;
      SELECT relkind,relowner INTO rel FROM pg_class WHERE oid=relation_id;
      IF rel.relkind NOT IN ('r','p') OR pg_has_role('novvin_api',rel.relowner,'USAGE') OR
         pg_has_role('novvin_import_operator',rel.relowner,'USAGE') OR
         has_table_privilege('novvin_api',relation_id,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
        RAISE EXCEPTION 'Unsafe imported relation privilege: %',table_name;
      END IF;
      -- Purge requires SELECT and DELETE, but these privileges belong only to
      -- the inaccessible definer. Runtime receives EXECUTE capabilities below.
      EXECUTE format('REVOKE ALL ON TABLE supabase_import.%I FROM novvin_import_operator',table_name);
      EXECUTE format('GRANT SELECT,DELETE ON TABLE supabase_import.%I TO novvin_import_operator',table_name);
      IF table_name IN ('posts','post_comments') THEN
        FOR key IN SELECT attname FROM pg_attribute WHERE attrelid=relation_id AND attnum>0 AND NOT attisdropped
          AND attname=ANY(CASE WHEN table_name='posts' THEN ARRAY['content','visibility','updated_at'] ELSE ARRAY['content','updated_at'] END) LOOP
          EXECUTE format('GRANT UPDATE(%I) ON TABLE supabase_import.%I TO novvin_import_operator',key,table_name);
        END LOOP;
      END IF;
    END LOOP;
  END IF;
END
$privileges$;

-- Helper functions are executable only by the definer. No function exists in
-- public, which is still owned by runtime for historical startup DDL.
ALTER FUNCTION novvin_import_mutation.allowed_tables() OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.normalize_values(text[]) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.array_matches(jsonb,text[]) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.assert_inventory() OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.assert_trigger_boundary() OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.patch_row(text,text,jsonb) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.delete_row(text,text) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.delete_post(text) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.delete_poll_votes(text,text[],boolean,text) OWNER TO novvin_import_operator;
ALTER FUNCTION novvin_import_mutation.purge_rows(text[],text) OWNER TO novvin_import_operator;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA novvin_import_mutation FROM PUBLIC, novvin_api;
GRANT EXECUTE ON FUNCTION novvin_import_mutation.patch_row(text,text,jsonb),
  novvin_import_mutation.delete_row(text,text),novvin_import_mutation.delete_post(text),
  novvin_import_mutation.delete_poll_votes(text,text[],boolean,text),
  novvin_import_mutation.purge_rows(text[],text) TO novvin_api;
COMMIT;
