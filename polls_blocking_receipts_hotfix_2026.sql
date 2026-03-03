-- ==========================================================
-- HOTFIX 2026: Polls 1-on-1, Blocking Logic & Receipts
-- ==========================================================

BEGIN;

-- 1) Fix Ambiguous poll_id in secure_get_poll_state
CREATE OR REPLACE FUNCTION public.secure_get_poll_state(
    p_poll_id UUID
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
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT p.scope_type, p.scope_id
      INTO v_scope_type, v_scope_id
    FROM public.message_polls p
    WHERE p.id = p_poll_id;

    IF v_scope_id IS NULL THEN
        RAISE EXCEPTION 'POLL_NOT_FOUND';
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
            SELECT 1 FROM public.chats c
            WHERE c.id = v_scope_id
              AND v_uid::TEXT = ANY(c.participants)
        ) THEN
            RAISE EXCEPTION 'POLL_ACCESS_DENIED';
        END IF;
    ELSE
        RAISE EXCEPTION 'POLL_SCOPE_INVALID';
    END IF;

    RETURN QUERY
    WITH poll_base AS (
        SELECT p.id, p.allows_multiple, p.is_closed, p.question
        FROM public.message_polls p
        WHERE p.id = p_poll_id
    ),
    total AS (
        SELECT COUNT(*)::BIGINT AS total_votes
        FROM public.message_poll_votes v
        WHERE v.poll_id = p_poll_id
    ),
    option_counts AS (
        SELECT
            o.id AS opt_id,
            COUNT(v.option_id)::BIGINT AS v_count
        FROM public.message_poll_options o
        LEFT JOIN public.message_poll_votes v
          ON v.option_id = o.id
         AND v.poll_id = o.poll_id
        WHERE o.poll_id = p_poll_id
        GROUP BY o.id
    )
    SELECT
        p_poll_id,
        o.id,
        o.option_text,
        o.display_order,
        COALESCE(oc.v_count, 0)::BIGINT,
        EXISTS (
            SELECT 1
            FROM public.message_poll_votes mv
            WHERE mv.poll_id = p_poll_id
              AND mv.option_id = o.id
              AND mv.user_id = v_uid
        ),
        t.total_votes,
        pb.allows_multiple,
        pb.is_closed,
        pb.question
    FROM public.message_poll_options o
    CROSS JOIN total t
    CROSS JOIN poll_base pb
    LEFT JOIN option_counts oc ON oc.opt_id = o.id
    WHERE o.poll_id = p_poll_id
    ORDER BY o.display_order ASC;
END;
$$;

-- 1.1) Fix Ambiguous poll_id in secure_vote_poll_option + enable chat scope
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

-- 2) Extend Polls to 1-on-1 Chats
ALTER TABLE public.message_polls 
    DROP CONSTRAINT IF EXISTS message_polls_scope_type_check;
ALTER TABLE public.message_polls 
    ADD CONSTRAINT message_polls_scope_type_check 
    CHECK (scope_type IN ('group', 'community', 'chat'));

ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS poll_id UUID REFERENCES public.message_polls(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_messages_poll_id ON public.messages(poll_id);

-- 3) blocking logic enhancements
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
    v_chat_participants TEXT[];
    v_other_user_id UUID;
    v_is_blocked BOOLEAN := FALSE;
    v_message public.messages;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT participants INTO v_chat_participants
    FROM public.chats
    WHERE id = p_chat_id;

    IF v_chat_participants IS NULL THEN
        RAISE EXCEPTION 'CHAT_NOT_FOUND';
    END IF;

    IF NOT (v_uid::TEXT = ANY(v_chat_participants)) THEN
        RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
    END IF;

    -- Block Check (only for 1-on-1 DMs)
    IF array_length(v_chat_participants, 1) = 2 THEN
        SELECT p.id INTO v_other_user_id
        FROM public.profiles p
        WHERE p.id::TEXT = ANY(v_chat_participants)
          AND p.id <> v_uid;

        IF v_other_user_id IS NOT NULL THEN
            SELECT EXISTS (
                SELECT 1 FROM public.blocked_users
                WHERE blocker_id = v_other_user_id
                  AND blocked_id = v_uid
            ) INTO v_is_blocked;
        END IF;
    END IF;

    IF v_is_blocked THEN
        RAISE EXCEPTION 'CHAT_BLOCKED_BY_RECIPIENT';
    END IF;

    -- Insert Message
    INSERT INTO public.messages (
        id,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        media_size,
        reply_to_id,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id,
        status,
        delivered_at -- new column
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_chat_id,
        v_uid,
        p_content,
        p_content_type,
        p_media_url,
        p_media_size,
        p_reply_to_id,
        p_is_forwarded,
        p_forwarded_from_message_id,
        p_forwarded_from_user_id,
        'sent',
        NULL
    )
    RETURNING * INTO v_message;

    RETURN v_message;
END;
$$;

-- 4) RLS for Messages (Blocking Awareness)
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
CREATE POLICY "Users can view messages in own chats" ON public.messages
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.chats c
        WHERE c.id = messages.chat_id
        AND auth.uid()::TEXT = ANY(c.participants)
    )
    AND NOT EXISTS (
        -- If I am looking at this chat, I should not see messages from users I have blocked
        SELECT 1 FROM public.blocked_users bu
        WHERE bu.blocker_id = auth.uid()
          AND bu.blocked_id = messages.sender_id
    )
);

-- 4.1) Realtime-ready blocking events
ALTER TABLE IF EXISTS public.blocked_users REPLICA IDENTITY FULL;

DO $$
BEGIN
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.blocked_users;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN undefined_object THEN
            NULL;
    END;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_blocked_users_blocker_blocked
    ON public.blocked_users(blocker_id, blocked_id);
CREATE INDEX IF NOT EXISTS idx_blocked_users_blocked_blocker
    ON public.blocked_users(blocked_id, blocker_id);

-- 5) Poll creation for DMs
CREATE OR REPLACE FUNCTION public.secure_create_chat_poll_message(
    p_chat_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_question TEXT DEFAULT '',
    p_options TEXT[] DEFAULT ARRAY[]::TEXT[],
    p_allows_multiple BOOLEAN DEFAULT FALSE
)
RETURNS public.messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_poll_id UUID;
    v_inserted public.messages;
    v_question TEXT;
    v_opt TEXT;
    v_order INT := 0;
    v_participants TEXT[];
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT participants INTO v_participants
    FROM public.chats
    WHERE id = p_chat_id;

    IF v_participants IS NULL OR NOT (v_uid::TEXT = ANY(v_participants)) THEN
        RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
    END IF;

    v_question := LEFT(TRIM(COALESCE(p_question, '')), 300);
    IF v_question = '' THEN
        RAISE EXCEPTION 'POLL_QUESTION_REQUIRED';
    END IF;

    IF p_options IS NULL OR COALESCE(array_length(p_options, 1), 0) < 2 THEN
        RAISE EXCEPTION 'POLL_MIN_OPTIONS';
    END IF;

    INSERT INTO public.message_polls (
        scope_type,
        scope_id,
        creator_id,
        question,
        allows_multiple
    ) VALUES (
        'chat',
        p_chat_id,
        v_uid,
        v_question,
        COALESCE(p_allows_multiple, FALSE)
    )
    RETURNING id INTO v_poll_id;

    FOREACH v_opt IN ARRAY p_options LOOP
        v_opt := LEFT(TRIM(COALESCE(v_opt, '')), 120);
        IF v_opt = '' THEN
            CONTINUE;
        END IF;

        INSERT INTO public.message_poll_options (poll_id, option_text, display_order)
        VALUES (v_poll_id, v_opt, v_order)
        ON CONFLICT (poll_id, option_text) DO NOTHING;

        v_order := v_order + 1;
    END LOOP;

    INSERT INTO public.messages (
        id,
        chat_id,
        sender_id,
        content,
        content_type,
        poll_id,
        status
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_chat_id,
        v_uid,
        v_question,
        'poll',
        v_poll_id,
        'sent'
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

-- 6) Delivered/Read status for messages
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION public.secure_mark_messages_delivered(
    p_chat_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.messages
    SET status = 'delivered',
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE chat_id = p_chat_id
      AND sender_id <> v_uid
      AND status = 'sent'
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_mark_messages_read(
    p_chat_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.messages
    SET status = 'read',
        read_at = COALESCE(read_at, NOW()),
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE chat_id = p_chat_id
      AND sender_id <> v_uid
      AND status IN ('delivered', 'received')
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_create_chat_poll_message(UUID, UUID, TEXT, TEXT[], BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_messages_delivered(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_messages_read(UUID) TO authenticated;

-- 7) Support for Group Messages
ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;
ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION public.secure_mark_group_messages_delivered(
    p_group_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.group_messages
    SET status = 'delivered',
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE group_id = p_group_id
      AND sender_id <> v_uid
      AND status = 'sent'
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_mark_group_messages_read(
    p_group_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.group_messages
    SET status = 'read',
        read_at = COALESCE(read_at, NOW()),
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE group_id = p_group_id
      AND sender_id <> v_uid
      AND status IN ('delivered', 'received')
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- 8) Support for Community Messages
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'sent';

CREATE OR REPLACE FUNCTION public.secure_mark_community_messages_delivered(
    p_community_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.community_messages
    SET status = 'delivered',
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE community_id = p_community_id
      AND sender_id <> v_uid
      AND status = 'sent'
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_mark_community_messages_read(
    p_community_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_count INTEGER := 0;
BEGIN
    UPDATE public.community_messages
    SET status = 'read',
        read_at = COALESCE(read_at, NOW()),
        delivered_at = COALESCE(delivered_at, NOW()),
        updated_at = NOW()
    WHERE community_id = p_community_id
      AND sender_id <> v_uid
      AND status IN ('delivered', 'received')
      AND deleted_for_all = false;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_mark_group_messages_delivered(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_group_messages_read(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_community_messages_delivered(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_community_messages_read(UUID) TO authenticated;

-- 9) Secure comment deletion (author OR post/video owner)
CREATE OR REPLACE FUNCTION public.secure_delete_post_comment(
    p_comment_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_post_id UUID;
    v_comment_author UUID;
    v_post_owner UUID;
    v_deleted INTEGER := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT pc.post_id, pc.user_id
      INTO v_post_id, v_comment_author
    FROM public.post_comments pc
    WHERE pc.id = p_comment_id;

    IF v_post_id IS NULL THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND';
    END IF;

    SELECT p.user_id
      INTO v_post_owner
    FROM public.posts p
    WHERE p.id = v_post_id;

    IF v_uid <> v_comment_author AND v_uid <> v_post_owner THEN
        RAISE EXCEPTION 'COMMENT_DELETE_FORBIDDEN';
    END IF;

    DELETE FROM public.post_comments
    WHERE id = p_comment_id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_delete_video_comment(
    p_comment_id UUID
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_video_id UUID;
    v_comment_author UUID;
    v_video_owner UUID;
    v_deleted INTEGER := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT vc.video_id, vc.user_id
      INTO v_video_id, v_comment_author
    FROM public.video_comments vc
    WHERE vc.id = p_comment_id;

    IF v_video_id IS NULL THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND';
    END IF;

    SELECT v.user_id
      INTO v_video_owner
    FROM public.videos v
    WHERE v.id = v_video_id;

    IF v_uid <> v_comment_author AND v_uid <> v_video_owner THEN
        RAISE EXCEPTION 'COMMENT_DELETE_FORBIDDEN';
    END IF;

    DELETE FROM public.video_comments
    WHERE id = p_comment_id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_delete_post_comment(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_delete_video_comment(UUID) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
