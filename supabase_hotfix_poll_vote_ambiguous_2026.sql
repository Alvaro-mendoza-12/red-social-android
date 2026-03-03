-- HOTFIX: secure_vote_poll_option poll_id ambiguous (SQLSTATE 42702)
-- Run in Supabase SQL Editor (project production), then test voting again.

BEGIN;

CREATE OR REPLACE FUNCTION public.secure_vote_poll_option(
    p_poll_id UUID,
    p_option_id UUID,
    p_selected BOOLEAN DEFAULT TRUE
)
RETURNS TABLE (
    poll_id UUID,
    option_id UUID,
    option_text TEXT,
    display_order INT,
    vote_count BIGINT,
    voted_by_me BOOLEAN,
    total_votes BIGINT,
    allows_multiple BOOLEAN,
    is_closed BOOLEAN,
    question TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_scope_type TEXT;
    v_scope_id UUID;
    v_allows_multiple BOOLEAN;
    v_is_closed BOOLEAN;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT p.scope_type, p.scope_id, p.allows_multiple, p.is_closed
      INTO v_scope_type, v_scope_id, v_allows_multiple, v_is_closed
    FROM public.message_polls p
    WHERE p.id = p_poll_id;

    IF v_scope_id IS NULL THEN
        RAISE EXCEPTION 'POLL_NOT_FOUND';
    END IF;

    IF v_is_closed THEN
        RAISE EXCEPTION 'POLL_CLOSED';
    END IF;

    IF v_scope_type = 'group' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = v_scope_id
              AND gm.user_id = v_uid
        ) THEN
            RAISE EXCEPTION 'POLL_ACCESS_DENIED';
        END IF;
    ELSIF v_scope_type = 'community' THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.community_members cm
            WHERE cm.community_id = v_scope_id
              AND cm.user_id = v_uid
        ) THEN
            RAISE EXCEPTION 'POLL_ACCESS_DENIED';
        END IF;
    ELSIF v_scope_type = 'chat' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.chats c
            WHERE c.id = v_scope_id
              AND v_uid::TEXT = ANY(c.participants)
        ) THEN
            RAISE EXCEPTION 'POLL_ACCESS_DENIED';
        END IF;
    ELSE
        RAISE EXCEPTION 'POLL_SCOPE_INVALID';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.message_poll_options o
        WHERE o.id = p_option_id
          AND o.poll_id = p_poll_id
    ) THEN
        RAISE EXCEPTION 'POLL_OPTION_INVALID';
    END IF;

    IF COALESCE(p_selected, TRUE) THEN
        IF NOT COALESCE(v_allows_multiple, FALSE) THEN
            DELETE FROM public.message_poll_votes
            WHERE message_poll_votes.poll_id = p_poll_id
              AND message_poll_votes.user_id = v_uid
              AND message_poll_votes.option_id <> p_option_id;
        END IF;

        INSERT INTO public.message_poll_votes (poll_id, option_id, user_id)
        VALUES (p_poll_id, p_option_id, v_uid)
        ON CONFLICT (poll_id, option_id, user_id)
        DO NOTHING;
    ELSE
        DELETE FROM public.message_poll_votes
        WHERE message_poll_votes.poll_id = p_poll_id
          AND message_poll_votes.option_id = p_option_id
          AND message_poll_votes.user_id = v_uid;
    END IF;

    RETURN QUERY
    SELECT * FROM public.secure_get_poll_state(p_poll_id);
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_vote_poll_option(UUID, UUID, BOOLEAN) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
