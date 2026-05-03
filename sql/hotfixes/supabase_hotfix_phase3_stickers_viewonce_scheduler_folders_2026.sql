-- ============================================================================
-- HOTFIX 2026: Messaging phase 3 (stickers + view-once + scheduler + folders)
-- ============================================================================
-- Scope:
-- 1) Sticker catalog + packs + favorites + search helpers
-- 2) View-once anti-double-consume (cross-device)
-- 3) Scheduled jobs + self-destruct metadata for dispatch pipeline
-- 4) Chat auto-translation preferences
-- 5) Chat folders and summary cache
--
-- Safe to run multiple times (IF NOT EXISTS / CREATE OR REPLACE).

BEGIN;

-- ---------------------------------------------------------------------------
-- 1) Stickers
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.sticker_catalog_items (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    media_url TEXT,
    thumbnail_url TEXT,
    is_animated BOOLEAN NOT NULL DEFAULT FALSE,
    tags TEXT[] NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_official BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sticker_catalog_active ON public.sticker_catalog_items(is_active, is_official);
CREATE INDEX IF NOT EXISTS idx_sticker_catalog_tags_gin ON public.sticker_catalog_items USING GIN(tags);

CREATE TABLE IF NOT EXISTS public.sticker_packs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    cover_sticker_id TEXT,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_sticker_packs_owner ON public.sticker_packs(owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sticker_packs_public ON public.sticker_packs(is_public, created_at DESC);

CREATE TABLE IF NOT EXISTS public.sticker_pack_items (
    pack_id UUID NOT NULL REFERENCES public.sticker_packs(id) ON DELETE CASCADE,
    sticker_id TEXT NOT NULL,
    media_url TEXT,
    label TEXT,
    tags TEXT[] NOT NULL DEFAULT '{}',
    is_animated BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pack_id, sticker_id)
);

CREATE INDEX IF NOT EXISTS idx_sticker_pack_items_pack ON public.sticker_pack_items(pack_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_sticker_pack_items_tags_gin ON public.sticker_pack_items USING GIN(tags);

CREATE TABLE IF NOT EXISTS public.user_sticker_favorites (
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    sticker_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, sticker_id)
);

CREATE INDEX IF NOT EXISTS idx_user_sticker_favorites_created ON public.user_sticker_favorites(user_id, created_at DESC);

ALTER TABLE public.sticker_catalog_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sticker_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sticker_pack_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_sticker_favorites ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS sticker_catalog_read_policy ON public.sticker_catalog_items;
CREATE POLICY sticker_catalog_read_policy ON public.sticker_catalog_items
FOR SELECT USING (is_active = TRUE);

DROP POLICY IF EXISTS sticker_packs_select_policy ON public.sticker_packs;
CREATE POLICY sticker_packs_select_policy ON public.sticker_packs
FOR SELECT USING (
    owner_id = auth.uid()
    OR is_public = TRUE
);

DROP POLICY IF EXISTS sticker_packs_insert_policy ON public.sticker_packs;
CREATE POLICY sticker_packs_insert_policy ON public.sticker_packs
FOR INSERT WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS sticker_packs_update_policy ON public.sticker_packs;
CREATE POLICY sticker_packs_update_policy ON public.sticker_packs
FOR UPDATE USING (owner_id = auth.uid())
WITH CHECK (owner_id = auth.uid());

DROP POLICY IF EXISTS sticker_packs_delete_policy ON public.sticker_packs;
CREATE POLICY sticker_packs_delete_policy ON public.sticker_packs
FOR DELETE USING (owner_id = auth.uid());

DROP POLICY IF EXISTS sticker_pack_items_select_policy ON public.sticker_pack_items;
CREATE POLICY sticker_pack_items_select_policy ON public.sticker_pack_items
FOR SELECT USING (
    EXISTS (
        SELECT 1
        FROM public.sticker_packs p
        WHERE p.id = sticker_pack_items.pack_id
          AND (p.owner_id = auth.uid() OR p.is_public = TRUE)
    )
);

DROP POLICY IF EXISTS sticker_pack_items_write_policy ON public.sticker_pack_items;
CREATE POLICY sticker_pack_items_write_policy ON public.sticker_pack_items
FOR ALL USING (
    EXISTS (
        SELECT 1
        FROM public.sticker_packs p
        WHERE p.id = sticker_pack_items.pack_id
          AND p.owner_id = auth.uid()
    )
)
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.sticker_packs p
        WHERE p.id = sticker_pack_items.pack_id
          AND p.owner_id = auth.uid()
    )
);

DROP POLICY IF EXISTS user_sticker_favorites_select_policy ON public.user_sticker_favorites;
CREATE POLICY user_sticker_favorites_select_policy ON public.user_sticker_favorites
FOR SELECT USING (user_id = auth.uid());

DROP POLICY IF EXISTS user_sticker_favorites_write_policy ON public.user_sticker_favorites;
CREATE POLICY user_sticker_favorites_write_policy ON public.user_sticker_favorites
FOR ALL USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

CREATE OR REPLACE FUNCTION public.secure_toggle_sticker_favorite(
    p_sticker_id TEXT
)
RETURNS TABLE (
    sticker_id TEXT,
    is_favorite BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_id TEXT := LEFT(TRIM(COALESCE(p_sticker_id, '')), 160);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF v_id = '' THEN
        RAISE EXCEPTION 'STICKER_REQUIRED';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_sticker_favorites f
        WHERE f.user_id = v_uid
          AND f.sticker_id = v_id
    ) THEN
        DELETE FROM public.user_sticker_favorites
        WHERE user_id = v_uid
          AND sticker_id = v_id;

        RETURN QUERY SELECT v_id, FALSE;
    ELSE
        INSERT INTO public.user_sticker_favorites(user_id, sticker_id)
        VALUES (v_uid, v_id)
        ON CONFLICT DO NOTHING;

        RETURN QUERY SELECT v_id, TRUE;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_create_sticker_pack(
    p_name TEXT,
    p_description TEXT DEFAULT NULL,
    p_is_public BOOLEAN DEFAULT FALSE
)
RETURNS public.sticker_packs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_name TEXT := LEFT(TRIM(COALESCE(p_name, '')), 80);
    v_description TEXT := LEFT(TRIM(COALESCE(p_description, '')), 300);
    v_pack public.sticker_packs;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF v_name = '' THEN
        RAISE EXCEPTION 'PACK_NAME_REQUIRED';
    END IF;

    INSERT INTO public.sticker_packs(owner_id, name, description, is_public)
    VALUES (v_uid, v_name, NULLIF(v_description, ''), COALESCE(p_is_public, FALSE))
    ON CONFLICT (owner_id, name)
    DO UPDATE SET
        description = EXCLUDED.description,
        is_public = EXCLUDED.is_public,
        updated_at = NOW()
    RETURNING * INTO v_pack;

    RETURN v_pack;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_add_sticker_to_pack(
    p_pack_id UUID,
    p_sticker_id TEXT,
    p_media_url TEXT DEFAULT NULL,
    p_label TEXT DEFAULT NULL,
    p_tags TEXT[] DEFAULT '{}',
    p_is_animated BOOLEAN DEFAULT FALSE
)
RETURNS public.sticker_pack_items
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_sort INTEGER;
    v_item public.sticker_pack_items;
    v_sticker_id TEXT := LEFT(TRIM(COALESCE(p_sticker_id, '')), 160);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_pack_id IS NULL THEN
        RAISE EXCEPTION 'PACK_REQUIRED';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.sticker_packs p
        WHERE p.id = p_pack_id
          AND p.owner_id = v_uid
    ) THEN
        RAISE EXCEPTION 'PACK_ACCESS_DENIED';
    END IF;

    IF v_sticker_id = '' THEN
        v_sticker_id := 'custom_' || replace(gen_random_uuid()::TEXT, '-', '');
    END IF;

    SELECT COALESCE(MAX(sort_order), 0) + 1
      INTO v_sort
    FROM public.sticker_pack_items i
    WHERE i.pack_id = p_pack_id;

    INSERT INTO public.sticker_pack_items(
        pack_id,
        sticker_id,
        media_url,
        label,
        tags,
        is_animated,
        sort_order
    ) VALUES (
        p_pack_id,
        v_sticker_id,
        NULLIF(TRIM(COALESCE(p_media_url, '')), ''),
        NULLIF(LEFT(TRIM(COALESCE(p_label, '')), 120), ''),
        COALESCE(p_tags, '{}'),
        COALESCE(p_is_animated, FALSE),
        v_sort
    )
    ON CONFLICT (pack_id, sticker_id)
    DO UPDATE SET
        media_url = EXCLUDED.media_url,
        label = EXCLUDED.label,
        tags = EXCLUDED.tags,
        is_animated = EXCLUDED.is_animated
    RETURNING * INTO v_item;

    UPDATE public.sticker_packs
       SET cover_sticker_id = COALESCE(cover_sticker_id, v_item.sticker_id),
           updated_at = NOW()
     WHERE id = p_pack_id;

    RETURN v_item;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_search_stickers(
    p_query TEXT DEFAULT '',
    p_limit INTEGER DEFAULT 60
)
RETURNS TABLE (
    sticker_id TEXT,
    title TEXT,
    media_url TEXT,
    thumbnail_url TEXT,
    is_animated BOOLEAN,
    tags TEXT[],
    source TEXT,
    pack_id UUID,
    pack_name TEXT,
    is_favorite BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_query TEXT := LOWER(TRIM(COALESCE(p_query, '')));
    v_limit INTEGER := LEAST(GREATEST(COALESCE(p_limit, 60), 1), 200);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    RETURN QUERY
    WITH catalog AS (
        SELECT
            c.id::TEXT AS sticker_id,
            c.title,
            c.media_url,
            c.thumbnail_url,
            c.is_animated,
            c.tags,
            'catalog'::TEXT AS source,
            NULL::UUID AS pack_id,
            NULL::TEXT AS pack_name
        FROM public.sticker_catalog_items c
        WHERE c.is_active = TRUE
          AND (
            v_query = ''
            OR LOWER(c.title) LIKE '%' || v_query || '%'
            OR EXISTS (
                SELECT 1
                FROM unnest(c.tags) tag
                WHERE LOWER(tag) LIKE '%' || v_query || '%'
            )
          )
    ),
    packs AS (
        SELECT
            i.sticker_id,
            COALESCE(i.label, i.sticker_id) AS title,
            i.media_url,
            i.media_url AS thumbnail_url,
            i.is_animated,
            i.tags,
            'pack'::TEXT AS source,
            i.pack_id,
            p.name AS pack_name
        FROM public.sticker_pack_items i
        JOIN public.sticker_packs p ON p.id = i.pack_id
        WHERE (p.owner_id = v_uid OR p.is_public = TRUE)
          AND (
            v_query = ''
            OR LOWER(COALESCE(i.label, i.sticker_id)) LIKE '%' || v_query || '%'
            OR EXISTS (
                SELECT 1
                FROM unnest(i.tags) tag
                WHERE LOWER(tag) LIKE '%' || v_query || '%'
            )
          )
    ),
    merged AS (
        SELECT * FROM catalog
        UNION ALL
        SELECT * FROM packs
    )
    SELECT
        m.sticker_id,
        m.title,
        m.media_url,
        m.thumbnail_url,
        m.is_animated,
        m.tags,
        m.source,
        m.pack_id,
        m.pack_name,
        EXISTS (
            SELECT 1
            FROM public.user_sticker_favorites f
            WHERE f.user_id = v_uid
              AND f.sticker_id = m.sticker_id
        ) AS is_favorite
    FROM merged m
    ORDER BY is_favorite DESC, source DESC, title ASC
    LIMIT v_limit;
END;
$$;

-- ---------------------------------------------------------------------------
-- 2) View-once robust guard (single consume per message/user)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.message_view_once_consumptions (
    scope_type TEXT NOT NULL CHECK (scope_type IN ('dm', 'group', 'community')),
    message_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id TEXT,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scope_type, message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_view_once_user ON public.message_view_once_consumptions(user_id, consumed_at DESC);

ALTER TABLE public.message_view_once_consumptions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS message_view_once_select_policy ON public.message_view_once_consumptions;
CREATE POLICY message_view_once_select_policy ON public.message_view_once_consumptions
FOR SELECT USING (user_id = auth.uid());

DROP POLICY IF EXISTS message_view_once_insert_policy ON public.message_view_once_consumptions;
CREATE POLICY message_view_once_insert_policy ON public.message_view_once_consumptions
FOR INSERT WITH CHECK (user_id = auth.uid());

CREATE OR REPLACE FUNCTION public.secure_consume_view_once_message(
    p_scope_type TEXT,
    p_message_id UUID,
    p_device_id TEXT DEFAULT NULL
)
RETURNS TABLE (
    allowed BOOLEAN,
    already_consumed BOOLEAN,
    consumed_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_scope TEXT := LOWER(TRIM(COALESCE(p_scope_type, '')));
    v_existing TIMESTAMPTZ;
    v_inserted TIMESTAMPTZ;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_message_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_REQUIRED';
    END IF;

    IF v_scope NOT IN ('dm', 'group', 'community') THEN
        RAISE EXCEPTION 'INVALID_SCOPE';
    END IF;

    IF v_scope = 'dm' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.messages m
            JOIN public.chats c ON c.id = m.chat_id
            WHERE m.id = p_message_id
              AND v_uid::TEXT = ANY(c.participants)
              AND m.content_type IN ('view_once_image', 'view_once_video')
        ) THEN
            RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED_OR_NOT_VIEW_ONCE';
        END IF;
    ELSIF v_scope = 'group' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.group_messages m
            JOIN public.group_members gm ON gm.group_id = m.group_id
            WHERE m.id = p_message_id
              AND gm.user_id = v_uid
              AND m.content_type IN ('view_once_image', 'view_once_video')
        ) THEN
            RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED_OR_NOT_VIEW_ONCE';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM public.community_messages m
            JOIN public.community_members cm ON cm.community_id = m.community_id
            WHERE m.id = p_message_id
              AND cm.user_id = v_uid
              AND m.content_type IN ('view_once_image', 'view_once_video')
        ) THEN
            RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED_OR_NOT_VIEW_ONCE';
        END IF;
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended(v_scope || ':' || p_message_id::TEXT || ':' || v_uid::TEXT, 0)
    );

    SELECT c.consumed_at
      INTO v_existing
    FROM public.message_view_once_consumptions c
    WHERE c.scope_type = v_scope
      AND c.message_id = p_message_id
      AND c.user_id = v_uid
    LIMIT 1
    FOR UPDATE;

    IF v_existing IS NOT NULL THEN
        RETURN QUERY SELECT FALSE, TRUE, v_existing;
        RETURN;
    END IF;

    INSERT INTO public.message_view_once_consumptions(scope_type, message_id, user_id, device_id)
    VALUES (v_scope, p_message_id, v_uid, NULLIF(TRIM(COALESCE(p_device_id, '')), ''))
    ON CONFLICT (scope_type, message_id, user_id) DO NOTHING
    RETURNING consumed_at INTO v_inserted;

    IF v_inserted IS NULL THEN
        SELECT c.consumed_at
          INTO v_existing
        FROM public.message_view_once_consumptions c
        WHERE c.scope_type = v_scope
          AND c.message_id = p_message_id
          AND c.user_id = v_uid
        LIMIT 1;

        RETURN QUERY SELECT FALSE, TRUE, COALESCE(v_existing, NOW());
        RETURN;
    END IF;

    RETURN QUERY SELECT TRUE, FALSE, v_inserted;
END;
$$;

-- ---------------------------------------------------------------------------
-- 3) Scheduled jobs + auto-destroy metadata
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.scheduled_message_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    scope_type TEXT NOT NULL CHECK (scope_type IN ('dm', 'group', 'community')),
    target_id UUID NOT NULL,
    content TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'text',
    media_url TEXT,
    translate_to TEXT,
    self_destruct_seconds INTEGER,
    scheduled_for TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'cancelled', 'failed')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_owner ON public.scheduled_message_jobs(owner_id, scheduled_for DESC);
CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_pending ON public.scheduled_message_jobs(status, scheduled_for ASC);

ALTER TABLE public.scheduled_message_jobs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS scheduled_jobs_select_policy ON public.scheduled_message_jobs;
CREATE POLICY scheduled_jobs_select_policy ON public.scheduled_message_jobs
FOR SELECT USING (owner_id = auth.uid());

DROP POLICY IF EXISTS scheduled_jobs_write_policy ON public.scheduled_message_jobs;
CREATE POLICY scheduled_jobs_write_policy ON public.scheduled_message_jobs
FOR ALL USING (owner_id = auth.uid())
WITH CHECK (owner_id = auth.uid());

CREATE OR REPLACE FUNCTION public.secure_schedule_message_job(
    p_scope_type TEXT,
    p_target_id UUID,
    p_content TEXT,
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_scheduled_for TIMESTAMPTZ DEFAULT NULL,
    p_translate_to TEXT DEFAULT NULL,
    p_self_destruct_seconds INTEGER DEFAULT NULL
)
RETURNS public.scheduled_message_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_scope TEXT := LOWER(TRIM(COALESCE(p_scope_type, '')));
    v_scheduled TIMESTAMPTZ := COALESCE(p_scheduled_for, NOW() + INTERVAL '1 minute');
    v_job public.scheduled_message_jobs;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF v_scope NOT IN ('dm', 'group', 'community') THEN
        RAISE EXCEPTION 'INVALID_SCOPE';
    END IF;

    IF p_target_id IS NULL THEN
        RAISE EXCEPTION 'TARGET_REQUIRED';
    END IF;

    IF COALESCE(TRIM(p_content), '') = '' AND COALESCE(TRIM(p_media_url), '') = '' THEN
        RAISE EXCEPTION 'CONTENT_REQUIRED';
    END IF;

    IF v_scope = 'dm' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.chats c
            WHERE c.id = p_target_id
              AND v_uid::TEXT = ANY(c.participants)
        ) THEN
            RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
        END IF;
    ELSIF v_scope = 'group' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = p_target_id
              AND gm.user_id = v_uid
        ) THEN
            RAISE EXCEPTION 'GROUP_ACCESS_DENIED';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1 FROM public.community_members cm
            WHERE cm.community_id = p_target_id
              AND cm.user_id = v_uid
        ) THEN
            RAISE EXCEPTION 'COMMUNITY_ACCESS_DENIED';
        END IF;
    END IF;

    INSERT INTO public.scheduled_message_jobs(
        owner_id,
        scope_type,
        target_id,
        content,
        content_type,
        media_url,
        translate_to,
        self_destruct_seconds,
        scheduled_for
    ) VALUES (
        v_uid,
        v_scope,
        p_target_id,
        LEFT(COALESCE(p_content, ''), 4000),
        COALESCE(NULLIF(TRIM(p_content_type), ''), 'text'),
        NULLIF(TRIM(COALESCE(p_media_url, '')), ''),
        NULLIF(LOWER(TRIM(COALESCE(p_translate_to, ''))), ''),
        CASE
            WHEN p_self_destruct_seconds IS NULL OR p_self_destruct_seconds < 1 THEN NULL
            ELSE LEAST(p_self_destruct_seconds, 2592000)
        END,
        v_scheduled
    )
    RETURNING * INTO v_job;

    RETURN v_job;
END;
$$;

-- ---------------------------------------------------------------------------
-- 4) Chat translation settings
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.chat_translation_settings (
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    target_lang TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chat_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_translation_user ON public.chat_translation_settings(user_id, updated_at DESC);

ALTER TABLE public.chat_translation_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS chat_translation_select_policy ON public.chat_translation_settings;
CREATE POLICY chat_translation_select_policy ON public.chat_translation_settings
FOR SELECT USING (user_id = auth.uid());

DROP POLICY IF EXISTS chat_translation_write_policy ON public.chat_translation_settings;
CREATE POLICY chat_translation_write_policy ON public.chat_translation_settings
FOR ALL USING (user_id = auth.uid())
WITH CHECK (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1
        FROM public.chats c
        WHERE c.id = chat_translation_settings.chat_id
          AND auth.uid()::TEXT = ANY(c.participants)
    )
);

CREATE OR REPLACE FUNCTION public.secure_set_chat_translation_settings(
    p_chat_id UUID,
    p_enabled BOOLEAN,
    p_target_lang TEXT DEFAULT NULL
)
RETURNS public.chat_translation_settings
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_row public.chat_translation_settings;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_chat_id IS NULL THEN
        RAISE EXCEPTION 'CHAT_REQUIRED';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.chats c
        WHERE c.id = p_chat_id
          AND v_uid::TEXT = ANY(c.participants)
    ) THEN
        RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
    END IF;

    INSERT INTO public.chat_translation_settings(chat_id, user_id, enabled, target_lang)
    VALUES (
        p_chat_id,
        v_uid,
        COALESCE(p_enabled, FALSE),
        CASE WHEN COALESCE(p_enabled, FALSE) THEN NULLIF(LOWER(TRIM(COALESCE(p_target_lang, ''))), '') ELSE NULL END
    )
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET
        enabled = EXCLUDED.enabled,
        target_lang = EXCLUDED.target_lang,
        updated_at = NOW()
    RETURNING * INTO v_row;

    RETURN v_row;
END;
$$;

-- ---------------------------------------------------------------------------
-- 5) Chat folders + summary cache
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.chat_folders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    color_hex TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS public.chat_folder_chats (
    folder_id UUID NOT NULL REFERENCES public.chat_folders(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (folder_id, chat_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_folder_chats_chat ON public.chat_folder_chats(chat_id);

ALTER TABLE public.chat_folders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_folder_chats ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS chat_folders_select_policy ON public.chat_folders;
CREATE POLICY chat_folders_select_policy ON public.chat_folders
FOR SELECT USING (user_id = auth.uid());

DROP POLICY IF EXISTS chat_folders_write_policy ON public.chat_folders;
CREATE POLICY chat_folders_write_policy ON public.chat_folders
FOR ALL USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS chat_folder_chats_select_policy ON public.chat_folder_chats;
CREATE POLICY chat_folder_chats_select_policy ON public.chat_folder_chats
FOR SELECT USING (
    EXISTS (
        SELECT 1
        FROM public.chat_folders f
        WHERE f.id = chat_folder_chats.folder_id
          AND f.user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS chat_folder_chats_write_policy ON public.chat_folder_chats;
CREATE POLICY chat_folder_chats_write_policy ON public.chat_folder_chats
FOR ALL USING (
    EXISTS (
        SELECT 1
        FROM public.chat_folders f
        JOIN public.chats c ON c.id = chat_folder_chats.chat_id
        WHERE f.id = chat_folder_chats.folder_id
          AND f.user_id = auth.uid()
          AND auth.uid()::TEXT = ANY(c.participants)
    )
)
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.chat_folders f
        JOIN public.chats c ON c.id = chat_folder_chats.chat_id
        WHERE f.id = chat_folder_chats.folder_id
          AND f.user_id = auth.uid()
          AND auth.uid()::TEXT = ANY(c.participants)
    )
);

CREATE TABLE IF NOT EXISTS public.chat_summary_cache (
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    source_message_count INTEGER NOT NULL DEFAULT 0,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chat_id, user_id)
);

ALTER TABLE public.chat_summary_cache ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS chat_summary_select_policy ON public.chat_summary_cache;
CREATE POLICY chat_summary_select_policy ON public.chat_summary_cache
FOR SELECT USING (user_id = auth.uid());

DROP POLICY IF EXISTS chat_summary_write_policy ON public.chat_summary_cache;
CREATE POLICY chat_summary_write_policy ON public.chat_summary_cache
FOR ALL USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

GRANT EXECUTE ON FUNCTION public.secure_toggle_sticker_favorite(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_create_sticker_pack(TEXT, TEXT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_add_sticker_to_pack(UUID, TEXT, TEXT, TEXT, TEXT[], BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_search_stickers(TEXT, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_consume_view_once_message(TEXT, UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_schedule_message_job(TEXT, UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, TEXT, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_set_chat_translation_settings(UUID, BOOLEAN, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
