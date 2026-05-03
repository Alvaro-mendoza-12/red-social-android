-- ============================================================
-- HOTFIX 2026: Direct message reactions + forwarding metadata
-- ============================================================
-- Apply this in Supabase SQL Editor.

BEGIN;

ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS forwarded_from_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS forwarded_from_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL;

UPDATE public.messages
SET reactions = '{}'::jsonb
WHERE reactions IS NULL;

CREATE INDEX IF NOT EXISTS idx_messages_is_forwarded
    ON public.messages(is_forwarded);

CREATE OR REPLACE FUNCTION public.secure_send_message(
    p_chat_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_media_size BIGINT DEFAULT 0,
    p_reply_to_id UUID DEFAULT NULL,
    p_is_forwarded BOOLEAN DEFAULT FALSE,
    p_forwarded_from_message_id UUID DEFAULT NULL,
    p_forwarded_from_user_id UUID DEFAULT NULL
)
RETURNS public.messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.messages;
    v_preview TEXT;
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

    IF COALESCE(NULLIF(TRIM(p_content_type), ''), 'text') = 'text'
       AND COALESCE(TRIM(p_content), '') = ''
       AND COALESCE(TRIM(p_media_url), '') = '' THEN
        RAISE EXCEPTION 'MESSAGE_EMPTY';
    END IF;

    INSERT INTO public.messages (
        id,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        media_size,
        reply_to_id,
        status,
        is_edited,
        deleted_for_all,
        reactions,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_chat_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        COALESCE(p_media_size, 0),
        p_reply_to_id,
        'sent',
        FALSE,
        FALSE,
        '{}'::jsonb,
        COALESCE(p_is_forwarded, FALSE),
        p_forwarded_from_message_id,
        CASE
            WHEN COALESCE(p_is_forwarded, FALSE) THEN COALESCE(p_forwarded_from_user_id, v_uid)
            ELSE NULL
        END
    )
    RETURNING * INTO v_inserted;

    v_preview := CASE
        WHEN v_inserted.content_type = 'text' THEN v_inserted.content
        WHEN v_inserted.content_type = 'image' THEN '📷 Foto'
        WHEN v_inserted.content_type = 'video' THEN '🎥 Video'
        WHEN v_inserted.content_type = 'audio' THEN '🎤 Audio'
        ELSE 'Archivo'
    END;

    UPDATE public.chats
       SET last_message = v_preview,
           last_message_at = v_inserted.created_at,
           last_message_id = v_inserted.id,
           last_message_sender_id = v_uid,
           updated_at = NOW()
     WHERE id = p_chat_id;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_toggle_message_reaction(
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
    v_chat_id UUID;
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

    SELECT m.chat_id, COALESCE(m.reactions, '{}'::jsonb)
      INTO v_chat_id, v_reactions
    FROM public.messages m
    WHERE m.id = p_message_id
    FOR UPDATE;

    IF v_chat_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_NOT_FOUND';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.chats c
        WHERE c.id = v_chat_id
          AND v_uid::TEXT = ANY(c.participants)
    ) THEN
        RAISE EXCEPTION 'MESSAGE_ACCESS_DENIED';
    END IF;

    v_had_same_reaction := EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        WHERE arr.value = v_uid::TEXT
    );

    -- Remove current user from every emoji array and drop empty keys.
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

    -- If user already had same emoji, this is a toggle-off; otherwise add as new single reaction.
    IF NOT v_had_same_reaction THEN
        v_users := ARRAY(
            SELECT value
            FROM jsonb_array_elements_text(COALESCE(v_reactions -> v_emoji, '[]'::jsonb)) AS arr(value)
        );
        v_users := array_append(v_users, v_uid::TEXT);
        v_reactions := jsonb_set(v_reactions, ARRAY[v_emoji], to_jsonb(v_users), TRUE);
    END IF;

    RETURN QUERY
    UPDATE public.messages m
       SET reactions = v_reactions,
           updated_at = NOW()
     WHERE m.id = p_message_id
    RETURNING m.id, m.reactions;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_send_message(
    UUID,
    UUID,
    TEXT,
    TEXT,
    TEXT,
    BIGINT,
    UUID,
    BOOLEAN,
    UUID,
    UUID
) TO authenticated;

GRANT EXECUTE ON FUNCTION public.secure_toggle_message_reaction(UUID, TEXT)
TO authenticated;

COMMIT;
