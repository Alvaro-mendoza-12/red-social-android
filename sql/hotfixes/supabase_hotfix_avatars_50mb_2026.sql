-- ==========================================================
-- HOTFIX 2026: Avatar upload limit to 50MB
-- - Raises backend media gate for bucket "avatars"
-- - Updates storage bucket size limit when supported
-- ==========================================================

BEGIN;

DO $$
BEGIN
    BEGIN
        UPDATE storage.buckets
        SET file_size_limit = 50 * 1024 * 1024
        WHERE id = 'avatars';
    EXCEPTION
        WHEN undefined_table OR undefined_column OR insufficient_privilege THEN
            RAISE NOTICE 'Skipping storage.buckets file_size_limit update for avatars';
    END;
END;
$$;

CREATE OR REPLACE FUNCTION public.register_media_object(
    p_bucket_name TEXT,
    p_object_path TEXT,
    p_content_type TEXT,
    p_size_bytes BIGINT,
    p_sha256 TEXT DEFAULT NULL
)
RETURNS public.media_registry
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_bucket TEXT := lower(trim(COALESCE(p_bucket_name, '')));
    v_path TEXT := trim(COALESCE(p_object_path, ''));
    v_content_type TEXT := lower(trim(COALESCE(p_content_type, '')));
    v_sha256 TEXT := lower(trim(COALESCE(p_sha256, '')));
    v_guard RECORD;
    v_max_bytes BIGINT;
    v_registered public.media_registry;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF v_bucket = '' OR v_path = '' OR v_content_type = '' THEN
        RAISE EXCEPTION 'INVALID_MEDIA_METADATA';
    END IF;

    IF v_path LIKE '%..%' OR v_path LIKE '/%' OR v_path LIKE '%\\%' THEN
        RAISE EXCEPTION 'UNSAFE_MEDIA_PATH';
    END IF;

    v_max_bytes := CASE v_bucket
        WHEN 'avatars' THEN 50 * 1024 * 1024
        WHEN 'post-media' THEN 25 * 1024 * 1024
        WHEN 'chat-media' THEN 50 * 1024 * 1024
        WHEN 'group-media' THEN 60 * 1024 * 1024
        WHEN 'community-media' THEN 60 * 1024 * 1024
        WHEN 'videos' THEN 120 * 1024 * 1024
        ELSE 80 * 1024 * 1024
    END;

    IF p_size_bytes IS NULL OR p_size_bytes <= 0 OR p_size_bytes > v_max_bytes THEN
        RAISE EXCEPTION 'MEDIA_SIZE_NOT_ALLOWED';
    END IF;

    IF to_regprocedure('public.guard_rate_limit(uuid,text,text,integer,integer,integer)') IS NOT NULL THEN
        SELECT * INTO v_guard
          FROM public.guard_rate_limit(v_uid, 'media_register', v_bucket, 90, 60, 50);

        IF v_guard.allowed IS NOT TRUE THEN
            IF to_regprocedure('public.log_security_event(uuid,text,text,text,jsonb)') IS NOT NULL THEN
                PERFORM public.log_security_event(
                    v_uid,
                    'media_register',
                    'blocked',
                    v_bucket,
                    jsonb_build_object('retry_after_ms', COALESCE(v_guard.retry_after_ms, 0))
                );
            END IF;
            RAISE EXCEPTION 'RATE_LIMIT_MEDIA_REGISTER:%', COALESCE(v_guard.retry_after_ms, 0);
        END IF;
    END IF;

    IF v_sha256 <> '' THEN
        IF (
            SELECT COUNT(*)
            FROM public.media_registry
            WHERE owner_id = v_uid
              AND sha256 = v_sha256
              AND created_at >= NOW() - INTERVAL '10 minutes'
        ) >= 20 THEN
            RAISE EXCEPTION 'MEDIA_DUPLICATE_SPAM';
        END IF;
    ELSE
        v_sha256 := NULL;
    END IF;

    INSERT INTO public.media_registry (
        owner_id,
        bucket_name,
        object_path,
        content_type,
        size_bytes,
        sha256,
        status
    ) VALUES (
        v_uid,
        v_bucket,
        v_path,
        v_content_type,
        p_size_bytes,
        v_sha256,
        'registered'
    )
    ON CONFLICT (bucket_name, object_path)
    DO UPDATE SET
        owner_id = EXCLUDED.owner_id,
        content_type = EXCLUDED.content_type,
        size_bytes = EXCLUDED.size_bytes,
        sha256 = EXCLUDED.sha256,
        status = 'registered',
        updated_at = NOW()
    RETURNING * INTO v_registered;

    RETURN v_registered;
END;
$$;

GRANT EXECUTE ON FUNCTION public.register_media_object(TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
