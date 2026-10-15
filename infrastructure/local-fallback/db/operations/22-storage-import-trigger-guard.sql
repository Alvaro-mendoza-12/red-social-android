BEGIN;
DO $$ BEGIN
  IF current_database()<>'conectate_fallback' THEN RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_DATABASE_REQUIRED'; END IF;
  IF current_user='novvin_api' OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND rolsuper) THEN
    RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_ADMIN_REQUIRED';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='novvin_storage_binding_writer') THEN
    CREATE ROLE novvin_storage_binding_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='novvin_storage_binding_writer' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS(SELECT 1 FROM pg_auth_members WHERE roleid='novvin_storage_binding_writer'::regrole OR member='novvin_storage_binding_writer'::regrole) THEN
    RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_ROLE_UNSAFE';
  END IF;
END $$;
CREATE TABLE IF NOT EXISTS public.fallback_storage_read_origins (
    origin TEXT PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  );
CREATE TABLE IF NOT EXISTS public.fallback_storage_private_history (
    bucket_id TEXT NOT NULL, object_name TEXT NOT NULL,
    first_bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(bucket_id,object_name)
  );
CREATE TABLE IF NOT EXISTS public.fallback_storage_private_bindings (
    source_schema TEXT NOT NULL CHECK(source_schema IN ('public','supabase_import')),
    source_table TEXT NOT NULL,
    access_kind TEXT NOT NULL CHECK(access_kind IN ('dm','group','community','random','proximity')),
    message_id TEXT NOT NULL CHECK(length(message_id) BETWEEN 1 AND 128),
    bucket_id TEXT NOT NULL, object_name TEXT NOT NULL,
    PRIMARY KEY(source_schema,source_table,message_id,bucket_id,object_name),
    FOREIGN KEY(bucket_id,object_name) REFERENCES public.fallback_storage_objects(bucket_id,name) ON DELETE CASCADE
  );
CREATE INDEX IF NOT EXISTS idx_storage_private_bindings_object ON public.fallback_storage_private_bindings(bucket_id,object_name);
CREATE TABLE IF NOT EXISTS public.fallback_storage_content_history (
    bucket_id text NOT NULL, object_name text NOT NULL, first_bound_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(bucket_id,object_name)
  );
CREATE TABLE IF NOT EXISTS public.fallback_storage_content_authority (
    access_kind text NOT NULL CHECK(access_kind IN ('post','video','moment')),record_id text NOT NULL,
    deleted boolean NOT NULL DEFAULT false,updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(access_kind,record_id)
  );
CREATE TABLE IF NOT EXISTS public.fallback_storage_content_bindings (
    source_schema text NOT NULL CHECK(source_schema IN ('public','supabase_import')),source_table text NOT NULL,
    access_kind text NOT NULL CHECK(access_kind IN ('post','video','moment','profile')),record_id text NOT NULL CHECK(length(record_id) BETWEEN 1 AND 180),
    bucket_id text NOT NULL,object_name text NOT NULL,
    PRIMARY KEY(source_schema,source_table,record_id,bucket_id,object_name),
    FOREIGN KEY(bucket_id,object_name) REFERENCES public.fallback_storage_objects(bucket_id,name) ON DELETE CASCADE
  );
CREATE INDEX IF NOT EXISTS idx_storage_content_bindings_object ON public.fallback_storage_content_bindings(bucket_id,object_name);
DO $$ DECLARE relation text; BEGIN
  FOREACH relation IN ARRAY ARRAY['fallback_storage_read_origins','fallback_storage_private_history','fallback_storage_private_bindings',
    'fallback_storage_content_history','fallback_storage_content_bindings','fallback_storage_content_authority'] LOOP
    EXECUTE format('ALTER TABLE public.%I OWNER TO novvin_api',relation);
  END LOOP;
END $$;
CREATE SCHEMA IF NOT EXISTS novvin_storage_guard;
REVOKE ALL ON SCHEMA novvin_storage_guard FROM PUBLIC,novvin_api;
REVOKE CREATE ON SCHEMA novvin_storage_guard,public FROM novvin_storage_binding_writer;
GRANT USAGE ON SCHEMA novvin_storage_guard,public TO novvin_storage_binding_writer;
GRANT SELECT ON public.fallback_storage_read_origins,public.fallback_storage_objects TO novvin_storage_binding_writer;
GRANT SELECT,INSERT,UPDATE ON public.fallback_storage_private_history TO novvin_storage_binding_writer;
GRANT SELECT,INSERT,DELETE ON public.fallback_storage_private_bindings TO novvin_storage_binding_writer;
GRANT SELECT,INSERT ON public.fallback_storage_content_history TO novvin_storage_binding_writer;
GRANT SELECT,INSERT,DELETE ON public.fallback_storage_content_bindings TO novvin_storage_binding_writer;
GRANT SELECT,INSERT,UPDATE ON public.fallback_storage_content_authority TO novvin_storage_binding_writer;
CREATE OR REPLACE FUNCTION novvin_storage_guard.novvin_storage_uri_segment(encoded text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog,novvin_storage_guard AS $$
    DECLARE position_index int:=1; octets bytea:=''::bytea; character_value text; decoded text;
    BEGIN
      IF length(encoded)>4096 OR encoded='' THEN RETURN NULL; END IF;
      WHILE position_index<=length(encoded) LOOP
        character_value:=substr(encoded,position_index,1);
        IF character_value='%' THEN
          IF substr(encoded,position_index+1,2)!~'^[0-9A-Fa-f]{2}$' THEN RETURN NULL; END IF;
          octets:=octets||decode(substr(encoded,position_index+1,2),'hex'); position_index:=position_index+3;
        ELSE
          octets:=octets||convert_to(character_value,'UTF8'); position_index:=position_index+1;
        END IF;
      END LOOP;
      decoded:=convert_from(octets,'UTF8');
      IF decoded IN ('','.', '..') OR decoded~'[[:cntrl:]]' OR strpos(decoded,'/')>0
        OR strpos(decoded,chr(92))>0 OR decoded~'%[0-9A-Fa-f]{2}' THEN RETURN NULL; END IF;
      RETURN decoded;
    EXCEPTION WHEN data_exception THEN RETURN NULL;
    END $$;
CREATE OR REPLACE FUNCTION novvin_storage_guard.novvin_storage_object_reference(raw_url text)
    RETURNS TABLE(bucket_name text,object_name text)
    LANGUAGE plpgsql STABLE STRICT SET search_path=pg_catalog,novvin_storage_guard AS $$
    DECLARE parts text[]; port_parts text[]; port_value int; encoded_segment text; decoded_segment text; path_segments text[]:='{}'::text[];
    BEGIN
      IF length(raw_url)>4096 OR strpos(raw_url,'#')>0 THEN RETURN; END IF;
      parts:=regexp_match(split_part(raw_url,'?',1),'^(https://[^/]+)(/storage/v1/object/(?:public/|sign/)?)([^/]+)/(.*)$','i');
      IF parts IS NULL OR parts[1]~'[^ -~]' OR strpos(parts[1],'%')>0 OR strpos(parts[1],chr(92))>0 THEN RETURN; END IF;
      parts[1]:=lower(parts[1]);
      port_parts:=regexp_match(parts[1],'^(.+):([0-9]+)$');
      IF port_parts IS NOT NULL THEN
        port_value:=port_parts[2]::int;
        IF port_value NOT BETWEEN 1 AND 65535 THEN RETURN; END IF;
        parts[1]:=port_parts[1]||CASE WHEN port_value=443 THEN '' ELSE ':'||port_value::text END;
      END IF;
      IF NOT EXISTS(SELECT 1 FROM public.fallback_storage_read_origins o WHERE o.origin=parts[1]) THEN RETURN; END IF;
      bucket_name:=novvin_storage_guard.novvin_storage_uri_segment(parts[3]);
      IF bucket_name IS NULL OR bucket_name!~'^[a-zA-Z0-9_-]{1,128}$' THEN RETURN; END IF;
      FOREACH encoded_segment IN ARRAY string_to_array(parts[4],'/') LOOP
        decoded_segment:=novvin_storage_guard.novvin_storage_uri_segment(encoded_segment);
        IF decoded_segment IS NULL THEN RETURN; END IF;
        path_segments:=array_append(path_segments,decoded_segment);
      END LOOP;
      IF cardinality(path_segments)=0 THEN RETURN; END IF;
      object_name:=array_to_string(path_segments,'/'); RETURN NEXT;
    EXCEPTION WHEN data_exception THEN RETURN;
    END $$;
CREATE OR REPLACE FUNCTION novvin_storage_guard.novvin_record_private_media_history() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,novvin_storage_guard AS $$
    DECLARE previous jsonb; current_row jsonb; reference_urls text[]; binding_kind text; current_id text;
    BEGIN
      IF TG_OP<>'INSERT' THEN previous:=to_jsonb(OLD); END IF;
      IF TG_OP<>'DELETE' THEN current_row:=to_jsonb(NEW); END IF;
      reference_urls:=ARRAY[previous->>'media_url',previous->>'thumbnail_url',current_row->>'media_url',current_row->>'thumbnail_url'];
      INSERT INTO public.fallback_storage_private_history(bucket_id,object_name)
        SELECT DISTINCT o.bucket_id,o.name FROM unnest(reference_urls) u(reference_url)
        CROSS JOIN LATERAL novvin_storage_guard.novvin_storage_object_reference(u.reference_url) ref
        JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name
        ON CONFLICT(bucket_id,object_name) DO UPDATE SET last_bound_at=NOW();
      IF TG_OP<>'INSERT' THEN
        DELETE FROM public.fallback_storage_private_bindings WHERE source_schema=TG_TABLE_SCHEMA
          AND source_table=TG_TABLE_NAME AND message_id=lower(trim(previous->>'id'));
      END IF;
      IF TG_OP='DELETE' THEN RETURN NULL; END IF;
      binding_kind:=CASE TG_TABLE_NAME
        WHEN 'fallback_messages' THEN 'dm' WHEN 'messages' THEN 'dm'
        WHEN 'fallback_room_messages' THEN current_row->>'room_kind'
        WHEN 'group_messages' THEN 'group' WHEN 'community_messages' THEN 'community'
        WHEN 'fallback_random_chat_messages' THEN 'random' WHEN 'proximity_messages' THEN 'proximity' END;
      current_id:=lower(trim(current_row->>'id'));
      IF binding_kind IS NULL OR current_id IS NULL OR length(current_id) NOT BETWEEN 1 AND 128 THEN RETURN NULL; END IF;
      INSERT INTO public.fallback_storage_private_bindings(source_schema,source_table,access_kind,message_id,bucket_id,object_name)
        SELECT DISTINCT TG_TABLE_SCHEMA,TG_TABLE_NAME,binding_kind,current_id,o.bucket_id,o.name
        FROM unnest(ARRAY[current_row->>'media_url',current_row->>'thumbnail_url']) u(reference_url)
        CROSS JOIN LATERAL novvin_storage_guard.novvin_storage_object_reference(u.reference_url) ref
        JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name
        ON CONFLICT DO NOTHING;
      RETURN NULL;
    END $$;
CREATE OR REPLACE FUNCTION novvin_storage_guard.novvin_storage_content_urls(source_table text,content_row jsonb)
    RETURNS TABLE(reference_url text) LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog,novvin_storage_guard AS $$
    DECLARE fields text[];nested text;field text;value jsonb;source jsonb;
    BEGIN
      SELECT specification.fields,specification.nested INTO fields,nested FROM (VALUES ('fallback_posts','post','id',NULL,ARRAY['media_url','media_urls']::text[]),
('fallback_videos','video','id',NULL,ARRAY['video_url','cover_url','thumbnail_url','sound_url']::text[]),
('fallback_moments','moment','id',NULL,ARRAY['media_url','thumbnail_url','music_audio_url','music_url']::text[]),
('fallback_users','profile','id',NULL,ARRAY['avatar_url']::text[]),
('fallback_profile_overrides','profile','user_id','profile',ARRAY['avatar_url','avatarUrl','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']::text[]),
('posts','post','id',NULL,ARRAY['media_url','media_urls','image_url']::text[]),
('videos','video','id',NULL,ARRAY['video_url','videoUrl','cover_url','coverUrl','thumbnail_url','thumbnailUrl','sound_url','soundUrl']::text[]),
('moments','moment','id',NULL,ARRAY['media_url','thumbnail_url','music_audio_url','music_url']::text[]),
('profiles','profile','id',NULL,ARRAY['avatar_url','avatarUrl','photo_url','profile_image','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']::text[]))
        AS specification(table_name,kind,id_field,nested,fields) WHERE specification.table_name=source_table;
      IF fields IS NULL THEN RETURN; END IF;
      source:=CASE WHEN nested IS NULL THEN content_row ELSE content_row->nested END;
      IF jsonb_typeof(source)<>'object' THEN RETURN; END IF;
      FOREACH field IN ARRAY fields LOOP
        value:=source->field;
        IF jsonb_typeof(value)='string' THEN
          reference_url:=source->>field;
          -- Imports store media_urls as text. Decode only a complete JSON array;
          -- malformed array-like values cannot become object references.
          IF left(ltrim(reference_url),1)='[' THEN
            BEGIN
              value:=reference_url::jsonb;
            EXCEPTION WHEN data_exception THEN
              CONTINUE;
            END;
            IF jsonb_typeof(value)<>'array' THEN CONTINUE; END IF;
            RETURN QUERY SELECT entry#>>'{}' FROM jsonb_array_elements(value) entry WHERE jsonb_typeof(entry)='string';
          ELSE
            RETURN NEXT;
          END IF;
        ELSIF jsonb_typeof(value)='array' THEN
          RETURN QUERY SELECT entry#>>'{}' FROM jsonb_array_elements(value) entry WHERE jsonb_typeof(entry)='string';
        END IF;
      END LOOP;
    END $$;
CREATE OR REPLACE FUNCTION novvin_storage_guard.novvin_record_storage_content_bindings() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,novvin_storage_guard AS $$
    DECLARE previous jsonb;current_row jsonb;id_field text;kind text;record_id_value text;
    BEGIN
      SELECT specification.kind,specification.id_field INTO kind,id_field FROM (VALUES ('fallback_posts','post','id',NULL,ARRAY['media_url','media_urls']::text[]),
('fallback_videos','video','id',NULL,ARRAY['video_url','cover_url','thumbnail_url','sound_url']::text[]),
('fallback_moments','moment','id',NULL,ARRAY['media_url','thumbnail_url','music_audio_url','music_url']::text[]),
('fallback_users','profile','id',NULL,ARRAY['avatar_url']::text[]),
('fallback_profile_overrides','profile','user_id','profile',ARRAY['avatar_url','avatarUrl','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']::text[]),
('posts','post','id',NULL,ARRAY['media_url','media_urls','image_url']::text[]),
('videos','video','id',NULL,ARRAY['video_url','videoUrl','cover_url','coverUrl','thumbnail_url','thumbnailUrl','sound_url','soundUrl']::text[]),
('moments','moment','id',NULL,ARRAY['media_url','thumbnail_url','music_audio_url','music_url']::text[]),
('profiles','profile','id',NULL,ARRAY['avatar_url','avatarUrl','photo_url','profile_image','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']::text[]))
        AS specification(table_name,kind,id_field,nested,fields) WHERE specification.table_name=TG_TABLE_NAME;
      IF id_field IS NULL THEN RAISE EXCEPTION 'STORAGE_CONTENT_SOURCE_INVALID'; END IF;
      IF TG_OP<>'INSERT' THEN previous:=to_jsonb(OLD);END IF;
      IF TG_OP<>'DELETE' THEN current_row:=to_jsonb(NEW);END IF;
      INSERT INTO public.fallback_storage_content_history(bucket_id,object_name)
        SELECT DISTINCT object.bucket_id,object.name FROM (
          SELECT reference_url FROM novvin_storage_guard.novvin_storage_content_urls(TG_TABLE_NAME,previous)
          UNION SELECT reference_url FROM novvin_storage_guard.novvin_storage_content_urls(TG_TABLE_NAME,current_row)
        ) urls CROSS JOIN LATERAL novvin_storage_guard.novvin_storage_object_reference(urls.reference_url) reference
        JOIN public.fallback_storage_objects object ON object.bucket_id=reference.bucket_name AND object.name=reference.object_name
        ON CONFLICT DO NOTHING;
      IF TG_OP<>'INSERT' THEN
        DELETE FROM public.fallback_storage_content_bindings WHERE source_schema=TG_TABLE_SCHEMA AND source_table=TG_TABLE_NAME
          AND record_id=lower(trim(previous->>id_field));
      END IF;
      record_id_value:=lower(trim(COALESCE(current_row,previous)->>id_field));
      IF TG_TABLE_SCHEMA='public' AND kind IN ('post','video','moment') AND record_id_value IS NOT NULL THEN
        INSERT INTO public.fallback_storage_content_authority(access_kind,record_id,deleted)
          VALUES(kind,record_id_value,TG_OP='DELETE') ON CONFLICT(access_kind,record_id)
          DO UPDATE SET deleted=EXCLUDED.deleted,updated_at=now();
      END IF;
      IF TG_OP='DELETE' THEN RETURN NULL;END IF;
      IF record_id_value IS NULL OR length(record_id_value) NOT BETWEEN 1 AND 180 THEN RETURN NULL;END IF;
      INSERT INTO public.fallback_storage_content_bindings(source_schema,source_table,access_kind,record_id,bucket_id,object_name)
        SELECT DISTINCT TG_TABLE_SCHEMA,TG_TABLE_NAME,kind,record_id_value,object.bucket_id,object.name
        FROM novvin_storage_guard.novvin_storage_content_urls(TG_TABLE_NAME,current_row) urls
        CROSS JOIN LATERAL novvin_storage_guard.novvin_storage_object_reference(urls.reference_url) reference
        JOIN public.fallback_storage_objects object ON object.bucket_id=reference.bucket_name AND object.name=reference.object_name
        ON CONFLICT DO NOTHING;
      RETURN NULL;
    END $$;
ALTER FUNCTION novvin_storage_guard.novvin_storage_uri_segment(text) OWNER TO novvin_storage_binding_writer;
REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_storage_uri_segment(text) FROM PUBLIC,novvin_api;
ALTER FUNCTION novvin_storage_guard.novvin_storage_object_reference(text) OWNER TO novvin_storage_binding_writer;
REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_storage_object_reference(text) FROM PUBLIC,novvin_api;
ALTER FUNCTION novvin_storage_guard.novvin_record_private_media_history() OWNER TO novvin_storage_binding_writer;
REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_record_private_media_history() FROM PUBLIC,novvin_api;
ALTER FUNCTION novvin_storage_guard.novvin_storage_content_urls(text,jsonb) OWNER TO novvin_storage_binding_writer;
REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_storage_content_urls(text,jsonb) FROM PUBLIC,novvin_api;
ALTER FUNCTION novvin_storage_guard.novvin_record_storage_content_bindings() OWNER TO novvin_storage_binding_writer;
REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_record_storage_content_bindings() FROM PUBLIC,novvin_api;
DO $$ DECLARE relation text; BEGIN
  FOREACH relation IN ARRAY ARRAY['messages','group_messages','community_messages','proximity_messages'] LOOP
    IF to_regclass('supabase_import.'||relation) IS NOT NULL THEN
      EXECUTE format('CREATE OR REPLACE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON supabase_import.%I FOR EACH ROW EXECUTE FUNCTION novvin_storage_guard.novvin_record_private_media_history()',relation||'_private_media_history',relation);
    END IF;
  END LOOP;
  FOREACH relation IN ARRAY ARRAY['posts','videos','moments','profiles'] LOOP
    IF to_regclass('supabase_import.'||relation) IS NOT NULL THEN
      EXECUTE format('CREATE OR REPLACE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON supabase_import.%I FOR EACH ROW EXECUTE FUNCTION novvin_storage_guard.novvin_record_storage_content_bindings()',relation||'_storage_content_bindings',relation);
      EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON supabase_import.%I ((lower(id::text)))','idx_storage_content_'||relation||'_id',relation);
    END IF;
  END LOOP;
END $$;
COMMIT;
