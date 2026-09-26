-- P0: close legacy SECURITY DEFINER surfaces that could bypass RLS.
-- This migration is additive so already-applied historical migrations remain
-- immutable while the final database state is safe and deterministic.

BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT false;

-- Normalize installations created from the early camelCase schema. PostgreSQL
-- folded those unquoted names to lowercase (for example userId -> userid).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'userid'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'user_id'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN userid TO user_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'username'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'user_name'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN username TO user_name;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'userprofileimage'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'user_profile_image'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN userprofileimage TO user_profile_image;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'videourl'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'video_url'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN videourl TO video_url;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'thumbnailurl'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'thumbnail_url'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN thumbnailurl TO thumbnail_url;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'likecount'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'like_count'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN likecount TO like_count;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'commentcount'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'comment_count'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN commentcount TO comment_count;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'ispublic'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'is_public'
    ) THEN
        ALTER TABLE public.videos RENAME COLUMN ispublic TO is_public;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'video_comments' AND column_name = 'videoid'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'video_comments' AND column_name = 'video_id'
    ) THEN
        ALTER TABLE public.video_comments RENAME COLUMN videoid TO video_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'video_comments' AND column_name = 'userid'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'video_comments' AND column_name = 'user_id'
    ) THEN
        ALTER TABLE public.video_comments RENAME COLUMN userid TO user_id;
    END IF;
END $$;

-- These legacy counter helpers are not used by the current client. Keep them
-- backend-only so an authenticated user cannot forge social counters.
REVOKE ALL ON FUNCTION public.increment_followers_count(UUID)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.decrement_followers_count(UUID)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.increment_following_count(UUID)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.decrement_following_count(UUID)
    FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.increment_followers_count(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.decrement_followers_count(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.increment_following_count(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.decrement_following_count(UUID) TO service_role;

-- The legacy fallback still calls increment_post_count after a successful
-- upload. Bind both post counters to the JWT identity instead of trusting the
-- UUID supplied by the caller.
CREATE OR REPLACE FUNCTION public.increment_post_count(user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.role() IS DISTINCT FROM 'service_role' AND auth.uid() IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF user_id IS NULL OR (
        auth.role() IS DISTINCT FROM 'service_role' AND user_id <> auth.uid()
    ) THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;

    UPDATE public.profiles AS p
       SET post_count = GREATEST(COALESCE(p.post_count, 0), 0) + 1
     WHERE p.id = user_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.decrement_post_count(user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.role() IS DISTINCT FROM 'service_role' AND auth.uid() IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF user_id IS NULL OR (
        auth.role() IS DISTINCT FROM 'service_role' AND user_id <> auth.uid()
    ) THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;

    UPDATE public.profiles AS p
       SET post_count = GREATEST(COALESCE(p.post_count, 0) - 1, 0)
     WHERE p.id = user_id;
END;
$$;

REVOKE ALL ON FUNCTION public.increment_post_count(UUID)
    FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.decrement_post_count(UUID)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.increment_post_count(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.decrement_post_count(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.increment_post_count(UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.decrement_post_count(UUID) TO service_role;

-- A caller may only leave a chat in which its JWT identity participates.
CREATE OR REPLACE FUNCTION public.leave_chat(chat_id_param UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    current_user_id UUID := auth.uid();
BEGIN
    IF current_user_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF chat_id_param IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'CHAT_ID_REQUIRED', ERRCODE = '22023';
    END IF;

    UPDATE public.chats AS c
       SET participants = array_remove(c.participants, current_user_id::TEXT),
           updated_at = now()
     WHERE c.id = chat_id_param
       AND current_user_id::TEXT = ANY(c.participants);

    IF NOT FOUND THEN
        RAISE EXCEPTION USING MESSAGE = 'CHAT_NOT_FOUND_OR_FORBIDDEN', ERRCODE = '42501';
    END IF;

    DELETE FROM public.chats AS c
     WHERE c.id = chat_id_param
       AND cardinality(c.participants) = 0;
END;
$$;

REVOKE ALL ON FUNCTION public.leave_chat(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.leave_chat(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.leave_chat(UUID) TO service_role;

-- Trigger helpers must not be callable as ordinary public RPC surfaces.
REVOKE ALL ON FUNCTION public.handle_new_message()
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.update_updated_at_column()
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.posts_tsvector_trigger()
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.videos_tsvector_trigger()
    FROM PUBLIC, anon, authenticated;

-- Hashtag auto-tagging writes through protected join tables. Run it with a
-- fixed search path, but only for the owner represented by the JWT (or the
-- backend service role). Rebuild associations on edits so counters do not grow
-- every time the same content is saved.
CREATE OR REPLACE FUNCTION public.auto_tag_post()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    current_hashtag_id UUID;
    removed_hashtag_id UUID;
    inserted_rows INTEGER;
BEGIN
    IF auth.role() IS DISTINCT FROM 'service_role'
       AND (auth.uid() IS NULL OR NEW.user_id IS DISTINCT FROM auth.uid()) THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;

    FOR removed_hashtag_id IN
        DELETE FROM public.post_hashtags AS ph
         WHERE ph.post_id = NEW.id
         RETURNING ph.hashtag_id
    LOOP
        UPDATE public.hashtags AS h
           SET post_count = GREATEST(COALESCE(h.post_count, 0) - 1, 0),
               updated_at = now()
         WHERE h.id = removed_hashtag_id;
    END LOOP;

    tags := COALESCE(public.extract_hashtags(NEW.content, 5), '{}'::TEXT[]);
    FOREACH current_tag IN ARRAY tags LOOP
        INSERT INTO public.hashtags(name)
        VALUES (current_tag)
        ON CONFLICT (name) DO NOTHING;

        SELECT h.id
          INTO current_hashtag_id
          FROM public.hashtags AS h
         WHERE h.name = current_tag;

        INSERT INTO public.post_hashtags(post_id, hashtag_id)
        VALUES (NEW.id, current_hashtag_id)
        ON CONFLICT DO NOTHING;
        GET DIAGNOSTICS inserted_rows = ROW_COUNT;

        IF inserted_rows = 1 THEN
            UPDATE public.hashtags AS h
               SET post_count = COALESCE(h.post_count, 0) + 1,
                   updated_at = now()
             WHERE h.id = current_hashtag_id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.auto_tag_video()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    current_hashtag_id UUID;
    removed_hashtag_id UUID;
    inserted_rows INTEGER;
BEGIN
    IF auth.role() IS DISTINCT FROM 'service_role'
       AND (auth.uid() IS NULL OR NEW.user_id IS DISTINCT FROM auth.uid()) THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;

    FOR removed_hashtag_id IN
        DELETE FROM public.video_hashtags AS vh
         WHERE vh.video_id = NEW.id
         RETURNING vh.hashtag_id
    LOOP
        UPDATE public.hashtags AS h
           SET video_count = GREATEST(COALESCE(h.video_count, 0) - 1, 0),
               updated_at = now()
         WHERE h.id = removed_hashtag_id;
    END LOOP;

    tags := COALESCE(public.extract_hashtags(NEW.description, 5), '{}'::TEXT[]);
    FOREACH current_tag IN ARRAY tags LOOP
        INSERT INTO public.hashtags(name)
        VALUES (current_tag)
        ON CONFLICT (name) DO NOTHING;

        SELECT h.id
          INTO current_hashtag_id
          FROM public.hashtags AS h
         WHERE h.name = current_tag;

        INSERT INTO public.video_hashtags(video_id, hashtag_id)
        VALUES (NEW.id, current_hashtag_id)
        ON CONFLICT DO NOTHING;
        GET DIAGNOSTICS inserted_rows = ROW_COUNT;

        IF inserted_rows = 1 THEN
            UPDATE public.hashtags AS h
               SET video_count = COALESCE(h.video_count, 0) + 1,
                   updated_at = now()
             WHERE h.id = current_hashtag_id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.auto_tag_post()
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.auto_tag_video()
    FROM PUBLIC, anon, authenticated;

-- Public hashtag metadata remains readable, but all expensive requests are
-- normalized and bounded.
CREATE OR REPLACE FUNCTION public.search_hashtags(
    p_query TEXT,
    p_limit INT DEFAULT 10
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    safe_query TEXT := left(
        regexp_replace(lower(btrim(COALESCE(p_query, ''))), '[^[:alnum:]_]', '', 'g'),
        64
    );
    safe_limit INT := LEAST(GREATEST(COALESCE(p_limit, 10), 1), 25);
BEGIN
    IF auth.role() NOT IN ('anon', 'authenticated', 'service_role') THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_ROLE_REQUIRED', ERRCODE = '42501';
    END IF;
    IF safe_query = '' THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
      FROM public.hashtags AS h
     WHERE h.name ILIKE ('%' || safe_query || '%')
     ORDER BY h.trending_score DESC, h.post_count + h.video_count DESC, h.id
     LIMIT safe_limit;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_trending_hashtags(
    p_days_back INT DEFAULT 7,
    p_limit INT DEFAULT 20
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    safe_days INT := LEAST(GREATEST(COALESCE(p_days_back, 7), 1), 90);
    safe_limit INT := LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50);
BEGIN
    IF auth.role() NOT IN ('anon', 'authenticated', 'service_role') THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_ROLE_REQUIRED', ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
      FROM public.hashtags AS h
     WHERE h.updated_at >= now() - make_interval(days => safe_days)
        OR h.trending_score > 0
     ORDER BY h.trending_score DESC, h.updated_at DESC, h.id
     LIMIT safe_limit;
END;
$$;

-- Content lookups explicitly reproduce the visibility rules because a
-- SECURITY DEFINER function bypasses table RLS by design.
CREATE OR REPLACE FUNCTION public.get_posts_by_hashtag(
    p_hashtag_name TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    id UUID,
    user_id UUID,
    content TEXT,
    media_urls JSONB,
    created_at TIMESTAMPTZ,
    like_count INT,
    comment_count INT,
    share_count INT,
    is_verified BOOLEAN
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    actor_id UUID := auth.uid();
    safe_name TEXT := left(lower(btrim(COALESCE(p_hashtag_name, ''))), 50);
    safe_limit INT := LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50);
    safe_offset INT := LEAST(GREATEST(COALESCE(p_offset, 0), 0), 5000);
BEGIN
    IF actor_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF safe_name = '' THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT p.id, p.user_id, p.content, p.media_urls, p.created_at,
           p.like_count, p.comment_count, p.share_count, u.is_verified
      FROM public.posts AS p
      JOIN public.post_hashtags AS ph ON ph.post_id = p.id
      JOIN public.hashtags AS h ON h.id = ph.hashtag_id
      LEFT JOIN public.profiles AS u ON u.id = p.user_id
     WHERE h.name = safe_name
       AND (
           p.visibility = 'public'
           OR p.user_id = actor_id
           OR (
               p.visibility = 'followers'
               AND EXISTS (
                   SELECT 1
                     FROM public.follows AS f
                    WHERE f.follower_id = actor_id
                      AND f.following_id = p.user_id
               )
           )
       )
       AND (
           u.id IS NULL
           OR COALESCE(u.is_suspended, false) = false
           OR COALESCE(u.suspension_end_date, u.suspended_until) <= now()
       )
     ORDER BY p.created_at DESC, p.id
     LIMIT safe_limit OFFSET safe_offset;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_videos_by_hashtag(
    p_hashtag_name TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    id UUID,
    title TEXT,
    description TEXT,
    video_url TEXT,
    thumbnail_url TEXT,
    user_id UUID,
    created_at TIMESTAMPTZ,
    view_count INT,
    like_count INT,
    is_verified BOOLEAN
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    actor_id UUID := auth.uid();
    safe_name TEXT := left(lower(btrim(COALESCE(p_hashtag_name, ''))), 50);
    safe_limit INT := LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50);
    safe_offset INT := LEAST(GREATEST(COALESCE(p_offset, 0), 0), 5000);
BEGIN
    IF actor_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF safe_name = '' THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT v.id, v.title, v.description, v.video_url, v.thumbnail_url,
           v.user_id, v.created_at, v.views, v.like_count, u.is_verified
      FROM public.videos AS v
      JOIN public.video_hashtags AS vh ON vh.video_id = v.id
      JOIN public.hashtags AS h ON h.id = vh.hashtag_id
      LEFT JOIN public.profiles AS u ON u.id = v.user_id
     WHERE h.name = safe_name
       AND (COALESCE(v.is_public, true) = true OR v.user_id = actor_id)
       AND (
           u.id IS NULL
           OR COALESCE(u.is_suspended, false) = false
           OR COALESCE(u.suspension_end_date, u.suspended_until) <= now()
       )
     ORDER BY v.created_at DESC, v.id
     LIMIT safe_limit OFFSET safe_offset;
END;
$$;

CREATE OR REPLACE FUNCTION public.search_content(
    p_query TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    content_type TEXT,
    content_id UUID,
    title TEXT,
    description TEXT,
    author_username TEXT,
    author_display_name TEXT,
    hashtags TEXT[],
    rank REAL
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    actor_id UUID := auth.uid();
    safe_query TEXT := left(btrim(COALESCE(p_query, '')), 120);
    safe_limit INT := LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50);
    safe_offset INT := LEAST(GREATEST(COALESCE(p_offset, 0), 0), 5000);
BEGIN
    IF actor_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF safe_query = '' THEN
        RETURN;
    END IF;

    RETURN QUERY
    WITH post_results AS (
        SELECT 'post'::TEXT AS content_type,
               p.id AS content_id,
               p.content AS title,
               NULL::TEXT AS description,
               u.username AS author_username,
               u.display_name AS author_display_name,
               COALESCE(
                   ARRAY_AGG(DISTINCT h.name) FILTER (WHERE h.name IS NOT NULL),
                   ARRAY[]::TEXT[]
               ) AS hashtags,
               ts_rank(p.content_tsv, plainto_tsquery('spanish', safe_query)) AS rank
          FROM public.posts AS p
          JOIN public.profiles AS u ON u.id = p.user_id
          LEFT JOIN public.post_hashtags AS ph ON ph.post_id = p.id
          LEFT JOIN public.hashtags AS h ON h.id = ph.hashtag_id
         WHERE p.content_tsv @@ plainto_tsquery('spanish', safe_query)
           AND (
               p.visibility = 'public'
               OR p.user_id = actor_id
               OR (
                   p.visibility = 'followers'
                   AND EXISTS (
                       SELECT 1 FROM public.follows AS f
                        WHERE f.follower_id = actor_id
                          AND f.following_id = p.user_id
                   )
               )
           )
           AND (
               COALESCE(u.is_suspended, false) = false
               OR COALESCE(u.suspension_end_date, u.suspended_until) <= now()
           )
         GROUP BY p.id, u.username, u.display_name, p.content_tsv
    ),
    video_results AS (
        SELECT 'video'::TEXT AS content_type,
               v.id AS content_id,
               v.title,
               v.description,
               u.username AS author_username,
               u.display_name AS author_display_name,
               COALESCE(
                   ARRAY_AGG(DISTINCT h.name) FILTER (WHERE h.name IS NOT NULL),
                   ARRAY[]::TEXT[]
               ) AS hashtags,
               ts_rank(v.description_tsv, plainto_tsquery('spanish', safe_query)) AS rank
          FROM public.videos AS v
          JOIN public.profiles AS u ON u.id = v.user_id
          LEFT JOIN public.video_hashtags AS vh ON vh.video_id = v.id
          LEFT JOIN public.hashtags AS h ON h.id = vh.hashtag_id
         WHERE v.description_tsv @@ plainto_tsquery('spanish', safe_query)
           AND (COALESCE(v.is_public, true) = true OR v.user_id = actor_id)
           AND (
               COALESCE(u.is_suspended, false) = false
               OR COALESCE(u.suspension_end_date, u.suspended_until) <= now()
           )
         GROUP BY v.id, u.username, u.display_name, v.description_tsv
    ),
    hashtag_results AS (
        SELECT 'hashtag'::TEXT AS content_type,
               h.id AS content_id,
               h.name AS title,
               NULL::TEXT AS description,
               NULL::TEXT AS author_username,
               NULL::TEXT AS author_display_name,
               ARRAY[]::TEXT[] AS hashtags,
               1.0::REAL AS rank
          FROM public.hashtags AS h
         WHERE h.name ILIKE (
             '%' || regexp_replace(lower(safe_query), '[^[:alnum:]_]', '', 'g') || '%'
         )
    )
    SELECT combined.content_type, combined.content_id, combined.title,
           combined.description, combined.author_username,
           combined.author_display_name, combined.hashtags, combined.rank
      FROM (
          SELECT * FROM post_results
          UNION ALL
          SELECT * FROM video_results
          UNION ALL
          SELECT * FROM hashtag_results
      ) AS combined
     ORDER BY combined.rank DESC, combined.content_type, combined.content_id
     LIMIT safe_limit OFFSET safe_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.search_hashtags(TEXT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_trending_hashtags(INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.search_hashtags(TEXT, INT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_trending_hashtags(INT, INT) TO anon, authenticated;

REVOKE ALL ON FUNCTION public.get_posts_by_hashtag(TEXT, INT, INT)
    FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.get_videos_by_hashtag(TEXT, INT, INT)
    FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.search_content(TEXT, INT, INT)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_posts_by_hashtag(TEXT, INT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_videos_by_hashtag(TEXT, INT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.search_content(TEXT, INT, INT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
