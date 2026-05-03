-- ==========================================================
-- HOTFIX: view-once consume race guard (cross-device safe)
-- Replaces secure_consume_view_once_message with conflict-safe logic.
-- ==========================================================

BEGIN;

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

GRANT EXECUTE ON FUNCTION public.secure_consume_view_once_message(TEXT, UUID, TEXT) TO authenticated;
NOTIFY pgrst, 'reload schema';

COMMIT;
