-- ============================================================================
-- HOTFIX 2026: Group/Community message reactions + forwarding metadata
-- ============================================================================
-- Apply in Supabase SQL Editor.

BEGIN;

ALTER TABLE public.group_messages
    ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS forwarded_from_message_id UUID REFERENCES public.group_messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS forwarded_from_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;

ALTER TABLE public.community_messages
    ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS forwarded_from_message_id UUID REFERENCES public.community_messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS forwarded_from_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;

UPDATE public.group_messages
SET reactions = '{}'::jsonb
WHERE reactions IS NULL;

UPDATE public.community_messages
SET reactions = '{}'::jsonb
WHERE reactions IS NULL;

CREATE INDEX IF NOT EXISTS idx_group_messages_is_forwarded ON public.group_messages(is_forwarded);
CREATE INDEX IF NOT EXISTS idx_group_messages_reactions_gin ON public.group_messages USING gin (reactions);
CREATE INDEX IF NOT EXISTS idx_community_messages_is_forwarded ON public.community_messages(is_forwarded);
CREATE INDEX IF NOT EXISTS idx_community_messages_reactions_gin ON public.community_messages USING gin (reactions);

CREATE OR REPLACE FUNCTION public.secure_send_group_message(
    p_group_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_reply_to_id UUID DEFAULT NULL,
    p_is_forwarded BOOLEAN DEFAULT FALSE,
    p_forwarded_from_message_id UUID DEFAULT NULL,
    p_forwarded_from_user_id UUID DEFAULT NULL
)
RETURNS public.group_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.group_messages;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_group_id IS NULL THEN
        RAISE EXCEPTION 'GROUP_REQUIRED';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.group_members gm
        WHERE gm.group_id = p_group_id
          AND gm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'GROUP_ACCESS_DENIED';
    END IF;

    IF COALESCE(NULLIF(TRIM(p_content_type), ''), 'text') = 'text'
       AND COALESCE(TRIM(p_content), '') = ''
       AND COALESCE(TRIM(p_media_url), '') = '' THEN
        RAISE EXCEPTION 'MESSAGE_EMPTY';
    END IF;

    INSERT INTO public.group_messages (
        id,
        group_id,
        sender_id,
        content,
        content_type,
        media_url,
        reply_to_id,
        status,
        reactions,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_group_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        p_reply_to_id,
        'sent',
        '{}'::jsonb,
        COALESCE(p_is_forwarded, FALSE),
        p_forwarded_from_message_id,
        CASE
            WHEN COALESCE(p_is_forwarded, FALSE) THEN COALESCE(p_forwarded_from_user_id, v_uid)
            ELSE NULL
        END
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_send_community_message(
    p_community_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_reply_to_id UUID DEFAULT NULL,
    p_is_forwarded BOOLEAN DEFAULT FALSE,
    p_forwarded_from_message_id UUID DEFAULT NULL,
    p_forwarded_from_user_id UUID DEFAULT NULL
)
RETURNS public.community_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.community_messages;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_community_id IS NULL THEN
        RAISE EXCEPTION 'COMMUNITY_REQUIRED';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.community_members cm
        WHERE cm.community_id = p_community_id
          AND cm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'COMMUNITY_ACCESS_DENIED';
    END IF;

    IF COALESCE(NULLIF(TRIM(p_content_type), ''), 'text') = 'text'
       AND COALESCE(TRIM(p_content), '') = ''
       AND COALESCE(TRIM(p_media_url), '') = '' THEN
        RAISE EXCEPTION 'MESSAGE_EMPTY';
    END IF;

    INSERT INTO public.community_messages (
        id,
        community_id,
        sender_id,
        content,
        content_type,
        media_url,
        reply_to_id,
        reactions,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_community_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        p_reply_to_id,
        '{}'::jsonb,
        COALESCE(p_is_forwarded, FALSE),
        p_forwarded_from_message_id,
        CASE
            WHEN COALESCE(p_is_forwarded, FALSE) THEN COALESCE(p_forwarded_from_user_id, v_uid)
            ELSE NULL
        END
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_toggle_group_message_reaction(
    p_message_id UUID,
    p_emoji TEXT
)
RETURNS TABLE (
    message_id UUID,
    reactions JSONB
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_group_id UUID;
    v_reactions JSONB := '{}'::jsonb;
    v_emoji TEXT;
    v_users TEXT[];
    v_had_same_reaction BOOLEAN := FALSE;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    v_emoji := LEFT(TRIM(COALESCE(p_emoji, '')), 16);
    IF v_emoji = '' THEN
        RAISE EXCEPTION 'EMOJI_REQUIRED';
    END IF;

    SELECT m.group_id, COALESCE(m.reactions, '{}'::jsonb)
      INTO v_group_id, v_reactions
    FROM public.group_messages m
    WHERE m.id = p_message_id
    FOR UPDATE;

    IF v_group_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_NOT_FOUND';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.group_members gm
        WHERE gm.group_id = v_group_id
          AND gm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED';
    END IF;

    v_had_same_reaction := EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        WHERE arr.value = v_uid::TEXT
    );

    v_reactions := (
        SELECT COALESCE(
            jsonb_object_agg(src.key, to_jsonb(src.filtered_users)),
            '{}'::jsonb
        )
        FROM (
            SELECT
                e.key,
                ARRAY(
                    SELECT value
                    FROM jsonb_array_elements_text(e.value) AS arr(value)
                    WHERE value <> v_uid::TEXT
                ) AS filtered_users
            FROM jsonb_each(COALESCE(v_reactions, '{}'::jsonb)) AS e
        ) AS src
        WHERE COALESCE(array_length(src.filtered_users, 1), 0) > 0
    );

    IF NOT v_had_same_reaction THEN
        v_users := ARRAY(
            SELECT value
            FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        );
        v_users := array_append(v_users, v_uid::TEXT);
        v_reactions := jsonb_set(v_reactions, ARRAY[v_emoji], to_jsonb(v_users), TRUE);
    END IF;

    RETURN QUERY
    UPDATE public.group_messages m
       SET reactions = v_reactions
     WHERE m.id = p_message_id
    RETURNING m.id, m.reactions;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_toggle_community_message_reaction(
    p_message_id UUID,
    p_emoji TEXT
)
RETURNS TABLE (
    message_id UUID,
    reactions JSONB
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_community_id UUID;
    v_reactions JSONB := '{}'::jsonb;
    v_emoji TEXT;
    v_users TEXT[];
    v_had_same_reaction BOOLEAN := FALSE;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    v_emoji := LEFT(TRIM(COALESCE(p_emoji, '')), 16);
    IF v_emoji = '' THEN
        RAISE EXCEPTION 'EMOJI_REQUIRED';
    END IF;

    SELECT m.community_id, COALESCE(m.reactions, '{}'::jsonb)
      INTO v_community_id, v_reactions
    FROM public.community_messages m
    WHERE m.id = p_message_id
    FOR UPDATE;

    IF v_community_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_NOT_FOUND';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.community_members cm
        WHERE cm.community_id = v_community_id
          AND cm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED';
    END IF;

    v_had_same_reaction := EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        WHERE arr.value = v_uid::TEXT
    );

    v_reactions := (
        SELECT COALESCE(
            jsonb_object_agg(src.key, to_jsonb(src.filtered_users)),
            '{}'::jsonb
        )
        FROM (
            SELECT
                e.key,
                ARRAY(
                    SELECT value
                    FROM jsonb_array_elements_text(e.value) AS arr(value)
                    WHERE value <> v_uid::TEXT
                ) AS filtered_users
            FROM jsonb_each(COALESCE(v_reactions, '{}'::jsonb)) AS e
        ) AS src
        WHERE COALESCE(array_length(src.filtered_users, 1), 0) > 0
    );

    IF NOT v_had_same_reaction THEN
        v_users := ARRAY(
            SELECT value
            FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        );
        v_users := array_append(v_users, v_uid::TEXT);
        v_reactions := jsonb_set(v_reactions, ARRAY[v_emoji], to_jsonb(v_users), TRUE);
    END IF;

    RETURN QUERY
    UPDATE public.community_messages m
       SET reactions = v_reactions
     WHERE m.id = p_message_id
    RETURNING m.id, m.reactions;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_send_group_message(
    UUID,
    UUID,
    TEXT,
    TEXT,
    TEXT,
    UUID,
    BOOLEAN,
    UUID,
    UUID
) TO authenticated;

GRANT EXECUTE ON FUNCTION public.secure_send_community_message(
    UUID,
    UUID,
    TEXT,
    TEXT,
    TEXT,
    UUID,
    BOOLEAN,
    UUID,
    UUID
) TO authenticated;

GRANT EXECUTE ON FUNCTION public.secure_toggle_group_message_reaction(UUID, TEXT)
TO authenticated;

GRANT EXECUTE ON FUNCTION public.secure_toggle_community_message_reaction(UUID, TEXT)
TO authenticated;

COMMIT;
