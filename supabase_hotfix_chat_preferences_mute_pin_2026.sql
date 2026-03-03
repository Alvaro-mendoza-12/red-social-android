-- ==========================================================
-- HOTFIX: Chat mute/pin persistence for private conversations
-- Adds chat_participants preference columns + secure RPC.
-- ==========================================================

BEGIN;

ALTER TABLE public.chat_participants
    ADD COLUMN IF NOT EXISTS is_muted BOOLEAN,
    ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN;

UPDATE public.chat_participants
   SET is_muted = COALESCE(is_muted, false),
       is_pinned = COALESCE(is_pinned, false)
 WHERE is_muted IS NULL
    OR is_pinned IS NULL;

ALTER TABLE public.chat_participants
    ALTER COLUMN is_muted SET DEFAULT false,
    ALTER COLUMN is_muted SET NOT NULL,
    ALTER COLUMN is_pinned SET DEFAULT false,
    ALTER COLUMN is_pinned SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_participants_user_pinned
    ON public.chat_participants (user_id, is_pinned DESC, last_read_at DESC);

CREATE OR REPLACE FUNCTION public.secure_set_chat_preferences(
    p_chat_id UUID,
    p_is_muted BOOLEAN DEFAULT NULL,
    p_is_pinned BOOLEAN DEFAULT NULL
)
RETURNS TABLE (
    chat_id UUID,
    user_id UUID,
    is_muted BOOLEAN,
    is_pinned BOOLEAN,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_now TIMESTAMPTZ := NOW();
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
           AND v_uid::text = ANY(c.participants)
    ) THEN
        RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
    END IF;

    INSERT INTO public.chat_participants (
        chat_id,
        user_id,
        joined_at,
        last_read_at,
        is_muted,
        is_pinned
    ) VALUES (
        p_chat_id,
        v_uid,
        v_now,
        v_now,
        COALESCE(p_is_muted, false),
        COALESCE(p_is_pinned, false)
    )
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET
        is_muted = COALESCE(p_is_muted, public.chat_participants.is_muted),
        is_pinned = COALESCE(p_is_pinned, public.chat_participants.is_pinned),
        last_read_at = COALESCE(public.chat_participants.last_read_at, v_now);

    RETURN QUERY
    SELECT
        cp.chat_id,
        cp.user_id,
        cp.is_muted,
        cp.is_pinned,
        v_now
    FROM public.chat_participants cp
    WHERE cp.chat_id = p_chat_id
      AND cp.user_id = v_uid
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_set_chat_preferences(UUID, BOOLEAN, BOOLEAN) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
