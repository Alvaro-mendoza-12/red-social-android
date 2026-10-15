'use strict';
// This shared DDL is used by runtime initialization and the generated,
// administrator-only imported-schema installation. No SECURITY DEFINER code.
const HISTORY_TABLE_STATEMENTS = Object.freeze([
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_read_origins (
    origin TEXT PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  )`,
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_private_history (
    bucket_id TEXT NOT NULL, object_name TEXT NOT NULL,
    first_bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(bucket_id,object_name)
  )`,
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_private_bindings (
    source_schema TEXT NOT NULL CHECK(source_schema IN ('public','supabase_import')),
    source_table TEXT NOT NULL,
    access_kind TEXT NOT NULL CHECK(access_kind IN ('dm','group','community','random','proximity')),
    message_id TEXT NOT NULL CHECK(length(message_id) BETWEEN 1 AND 128),
    bucket_id TEXT NOT NULL, object_name TEXT NOT NULL,
    PRIMARY KEY(source_schema,source_table,message_id,bucket_id,object_name),
    FOREIGN KEY(bucket_id,object_name) REFERENCES public.fallback_storage_objects(bucket_id,name) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_storage_private_bindings_object ON public.fallback_storage_private_bindings(bucket_id,object_name)`,
]);
const HISTORY_FUNCTION_STATEMENTS = Object.freeze([
  `CREATE OR REPLACE FUNCTION public.novvin_storage_uri_segment(encoded text) RETURNS text
    LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog,public AS $$
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
    END $$`,
  `CREATE OR REPLACE FUNCTION public.novvin_storage_object_reference(raw_url text)
    RETURNS TABLE(bucket_name text,object_name text)
    LANGUAGE plpgsql STABLE STRICT SET search_path=pg_catalog,public AS $$
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
      bucket_name:=public.novvin_storage_uri_segment(parts[3]);
      IF bucket_name IS NULL OR bucket_name!~'^[a-zA-Z0-9_-]{1,128}$' THEN RETURN; END IF;
      FOREACH encoded_segment IN ARRAY string_to_array(parts[4],'/') LOOP
        decoded_segment:=public.novvin_storage_uri_segment(encoded_segment);
        IF decoded_segment IS NULL THEN RETURN; END IF;
        path_segments:=array_append(path_segments,decoded_segment);
      END LOOP;
      IF cardinality(path_segments)=0 THEN RETURN; END IF;
      object_name:=array_to_string(path_segments,'/'); RETURN NEXT;
    EXCEPTION WHEN data_exception THEN RETURN;
    END $$`,
  `CREATE OR REPLACE FUNCTION public.novvin_record_private_media_history() RETURNS trigger
    LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
    DECLARE previous jsonb; current_row jsonb; reference_urls text[]; binding_kind text; current_id text;
    BEGIN
      IF TG_OP<>'INSERT' THEN previous:=to_jsonb(OLD); END IF;
      IF TG_OP<>'DELETE' THEN current_row:=to_jsonb(NEW); END IF;
      reference_urls:=ARRAY[previous->>'media_url',previous->>'thumbnail_url',current_row->>'media_url',current_row->>'thumbnail_url'];
      INSERT INTO public.fallback_storage_private_history(bucket_id,object_name)
        SELECT DISTINCT o.bucket_id,o.name FROM unnest(reference_urls) u(reference_url)
        CROSS JOIN LATERAL public.novvin_storage_object_reference(u.reference_url) ref
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
        CROSS JOIN LATERAL public.novvin_storage_object_reference(u.reference_url) ref
        JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name
        ON CONFLICT DO NOTHING;
      RETURN NULL;
    END $$`,
]);
module.exports={HISTORY_TABLE_STATEMENTS,HISTORY_FUNCTION_STATEMENTS};
