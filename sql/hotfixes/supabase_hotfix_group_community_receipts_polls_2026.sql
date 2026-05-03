-- ==========================================================
-- HOTFIX 2026: Group/Community read receipts + polls (WhatsApp style)
-- - Per-member read receipts for groups/communities
-- - Poll messages with secure vote RPCs
-- ==========================================================

BEGIN;

-- 1) Message schema extensions
ALTER TABLE public.group_messages
    ADD COLUMN IF NOT EXISTS poll_id UUID;

ALTER TABLE public.community_messages
    ADD COLUMN IF NOT EXISTS poll_id UUID;

-- 2) Poll core tables
CREATE TABLE IF NOT EXISTS public.message_polls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('group', 'community')),
    scope_id UUID NOT NULL,
    creator_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    allows_multiple BOOLEAN NOT NULL DEFAULT false,
    is_closed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_message_polls_scope ON public.message_polls(scope_type, scope_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.message_poll_options (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id UUID NOT NULL REFERENCES public.message_polls(id) ON DELETE CASCADE,
    option_text TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (poll_id, option_text)
);

CREATE INDEX IF NOT EXISTS idx_message_poll_options_poll ON public.message_poll_options(poll_id, display_order);

CREATE TABLE IF NOT EXISTS public.message_poll_votes (
    poll_id UUID NOT NULL REFERENCES public.message_polls(id) ON DELETE CASCADE,
    option_id UUID NOT NULL REFERENCES public.message_poll_options(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (poll_id, option_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_poll_votes_poll_user ON public.message_poll_votes(poll_id, user_id);
CREATE INDEX IF NOT EXISTS idx_message_poll_votes_option ON public.message_poll_votes(option_id);

-- 3) Read receipts tables
CREATE TABLE IF NOT EXISTS public.group_message_receipts (
    message_id UUID NOT NULL REFERENCES public.group_messages(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_message_receipts_group_user ON public.group_message_receipts(group_id, user_id, read_at DESC);
CREATE INDEX IF NOT EXISTS idx_group_message_receipts_message ON public.group_message_receipts(message_id, read_at DESC);

CREATE TABLE IF NOT EXISTS public.community_message_receipts (
    message_id UUID NOT NULL REFERENCES public.community_messages(id) ON DELETE CASCADE,
    community_id UUID NOT NULL REFERENCES public.communities(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_community_message_receipts_comm_user ON public.community_message_receipts(community_id, user_id, read_at DESC);
CREATE INDEX IF NOT EXISTS idx_community_message_receipts_message ON public.community_message_receipts(message_id, read_at DESC);

-- 4) FK wiring for poll_id (after polls table exists)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'group_messages_poll_id_fkey'
    ) THEN
        ALTER TABLE public.group_messages
            ADD CONSTRAINT group_messages_poll_id_fkey
            FOREIGN KEY (poll_id) REFERENCES public.message_polls(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'community_messages_poll_id_fkey'
    ) THEN
        ALTER TABLE public.community_messages
            ADD CONSTRAINT community_messages_poll_id_fkey
            FOREIGN KEY (poll_id) REFERENCES public.message_polls(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_group_messages_poll_id ON public.group_messages(poll_id);
CREATE INDEX IF NOT EXISTS idx_community_messages_poll_id ON public.community_messages(poll_id);

-- 5) RLS for new tables
ALTER TABLE public.message_polls ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_poll_options ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_poll_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_message_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_message_receipts ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS message_polls_select_policy ON public.message_polls;
CREATE POLICY message_polls_select_policy ON public.message_polls
FOR SELECT USING (
    (
        scope_type = 'group'
        AND EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = message_polls.scope_id
              AND gm.user_id = auth.uid()
        )
    )
    OR
    (
        scope_type = 'community'
        AND EXISTS (
            SELECT 1 FROM public.community_members cm
            WHERE cm.community_id = message_polls.scope_id
              AND cm.user_id = auth.uid()
        )
    )
);

DROP POLICY IF EXISTS message_polls_insert_policy ON public.message_polls;
CREATE POLICY message_polls_insert_policy ON public.message_polls
FOR INSERT WITH CHECK (
    creator_id = auth.uid()
);

DROP POLICY IF EXISTS message_poll_options_select_policy ON public.message_poll_options;
CREATE POLICY message_poll_options_select_policy ON public.message_poll_options
FOR SELECT USING (
    EXISTS (
        SELECT 1
        FROM public.message_polls p
        WHERE p.id = message_poll_options.poll_id
          AND (
                (p.scope_type = 'group' AND EXISTS (
                    SELECT 1 FROM public.group_members gm
                    WHERE gm.group_id = p.scope_id AND gm.user_id = auth.uid()
                ))
                OR
                (p.scope_type = 'community' AND EXISTS (
                    SELECT 1 FROM public.community_members cm
                    WHERE cm.community_id = p.scope_id AND cm.user_id = auth.uid()
                ))
          )
    )
);

DROP POLICY IF EXISTS message_poll_options_insert_policy ON public.message_poll_options;
CREATE POLICY message_poll_options_insert_policy ON public.message_poll_options
FOR INSERT WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.message_polls p
        WHERE p.id = message_poll_options.poll_id
          AND p.creator_id = auth.uid()
    )
);

DROP POLICY IF EXISTS message_poll_votes_select_policy ON public.message_poll_votes;
CREATE POLICY message_poll_votes_select_policy ON public.message_poll_votes
FOR SELECT USING (
    EXISTS (
        SELECT 1
        FROM public.message_polls p
        WHERE p.id = message_poll_votes.poll_id
          AND (
                (p.scope_type = 'group' AND EXISTS (
                    SELECT 1 FROM public.group_members gm
                    WHERE gm.group_id = p.scope_id AND gm.user_id = auth.uid()
                ))
                OR
                (p.scope_type = 'community' AND EXISTS (
                    SELECT 1 FROM public.community_members cm
                    WHERE cm.community_id = p.scope_id AND cm.user_id = auth.uid()
                ))
          )
    )
);

DROP POLICY IF EXISTS message_poll_votes_insert_policy ON public.message_poll_votes;
CREATE POLICY message_poll_votes_insert_policy ON public.message_poll_votes
FOR INSERT WITH CHECK (
    user_id = auth.uid()
);

DROP POLICY IF EXISTS message_poll_votes_delete_policy ON public.message_poll_votes;
CREATE POLICY message_poll_votes_delete_policy ON public.message_poll_votes
FOR DELETE USING (
    user_id = auth.uid()
);

DROP POLICY IF EXISTS group_message_receipts_select_policy ON public.group_message_receipts;
CREATE POLICY group_message_receipts_select_policy ON public.group_message_receipts
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.group_members gm
        WHERE gm.group_id = group_message_receipts.group_id
          AND gm.user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS group_message_receipts_write_policy ON public.group_message_receipts;
CREATE POLICY group_message_receipts_write_policy ON public.group_message_receipts
FOR ALL USING (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.group_members gm
        WHERE gm.group_id = group_message_receipts.group_id
          AND gm.user_id = auth.uid()
    )
) WITH CHECK (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.group_members gm
        WHERE gm.group_id = group_message_receipts.group_id
          AND gm.user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS community_message_receipts_select_policy ON public.community_message_receipts;
CREATE POLICY community_message_receipts_select_policy ON public.community_message_receipts
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.community_members cm
        WHERE cm.community_id = community_message_receipts.community_id
          AND cm.user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS community_message_receipts_write_policy ON public.community_message_receipts;
CREATE POLICY community_message_receipts_write_policy ON public.community_message_receipts
FOR ALL USING (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.community_members cm
        WHERE cm.community_id = community_message_receipts.community_id
          AND cm.user_id = auth.uid()
    )
) WITH CHECK (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1 FROM public.community_members cm
        WHERE cm.community_id = community_message_receipts.community_id
          AND cm.user_id = auth.uid()
    )
);

-- 6) Utility trigger to keep updated_at fresh
CREATE OR REPLACE FUNCTION public.tg_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_message_polls_updated_at ON public.message_polls;
CREATE TRIGGER trg_message_polls_updated_at
BEFORE UPDATE ON public.message_polls
FOR EACH ROW
EXECUTE FUNCTION public.tg_set_updated_at();

DROP TRIGGER IF EXISTS trg_group_message_receipts_updated_at ON public.group_message_receipts;
CREATE TRIGGER trg_group_message_receipts_updated_at
BEFORE UPDATE ON public.group_message_receipts
FOR EACH ROW
EXECUTE FUNCTION public.tg_set_updated_at();

DROP TRIGGER IF EXISTS trg_community_message_receipts_updated_at ON public.community_message_receipts;
CREATE TRIGGER trg_community_message_receipts_updated_at
BEFORE UPDATE ON public.community_message_receipts
FOR EACH ROW
EXECUTE FUNCTION public.tg_set_updated_at();

-- 7) Secure RPCs: mark as read
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

    INSERT INTO public.group_message_receipts (
        message_id,
        group_id,
        user_id,
        delivered_at,
        read_at
    )
    SELECT
        gm.id,
        gm.group_id,
        v_uid,
        COALESCE(gm.created_at, NOW()),
        NOW()
    FROM public.group_messages gm
    WHERE gm.group_id = p_group_id
      AND gm.sender_id IS DISTINCT FROM v_uid
      AND gm.deleted_for_all = false
    ON CONFLICT (message_id, user_id)
    DO UPDATE SET
        delivered_at = COALESCE(public.group_message_receipts.delivered_at, EXCLUDED.delivered_at),
        read_at = COALESCE(public.group_message_receipts.read_at, EXCLUDED.read_at),
        updated_at = NOW();

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

    INSERT INTO public.community_message_receipts (
        message_id,
        community_id,
        user_id,
        delivered_at,
        read_at
    )
    SELECT
        cm.id,
        cm.community_id,
        v_uid,
        COALESCE(cm.created_at, NOW()),
        NOW()
    FROM public.community_messages cm
    WHERE cm.community_id = p_community_id
      AND cm.sender_id IS DISTINCT FROM v_uid
      AND cm.deleted_for_all = false
    ON CONFLICT (message_id, user_id)
    DO UPDATE SET
        delivered_at = COALESCE(public.community_message_receipts.delivered_at, EXCLUDED.delivered_at),
        read_at = COALESCE(public.community_message_receipts.read_at, EXCLUDED.read_at),
        updated_at = NOW();

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- 8) Secure RPCs: get message receipts (who saw)
CREATE OR REPLACE FUNCTION public.secure_get_group_message_receipts(
    p_message_id UUID
)
RETURNS TABLE (
    user_id UUID,
    username TEXT,
    full_name TEXT,
    avatar_url TEXT,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_group_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT gm.group_id INTO v_group_id
    FROM public.group_messages gm
    WHERE gm.id = p_message_id;

    IF v_group_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_NOT_FOUND';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.group_members gm
        WHERE gm.group_id = v_group_id
          AND gm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'GROUP_ACCESS_DENIED';
    END IF;

    RETURN QUERY
    SELECT
        r.user_id,
        p.username,
        p.full_name,
        p.avatar_url,
        r.delivered_at,
        r.read_at
    FROM public.group_message_receipts r
    LEFT JOIN public.profiles p ON p.id = r.user_id
    WHERE r.message_id = p_message_id
    ORDER BY r.read_at DESC NULLS LAST, r.delivered_at DESC NULLS LAST;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_get_community_message_receipts(
    p_message_id UUID
)
RETURNS TABLE (
    user_id UUID,
    username TEXT,
    full_name TEXT,
    avatar_url TEXT,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_community_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT cm.community_id INTO v_community_id
    FROM public.community_messages cm
    WHERE cm.id = p_message_id;

    IF v_community_id IS NULL THEN
        RAISE EXCEPTION 'MESSAGE_NOT_FOUND';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.community_members cm
        WHERE cm.community_id = v_community_id
          AND cm.user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'COMMUNITY_ACCESS_DENIED';
    END IF;

    RETURN QUERY
    SELECT
        r.user_id,
        p.username,
        p.full_name,
        p.avatar_url,
        r.delivered_at,
        r.read_at
    FROM public.community_message_receipts r
    LEFT JOIN public.profiles p ON p.id = r.user_id
    WHERE r.message_id = p_message_id
    ORDER BY r.read_at DESC NULLS LAST, r.delivered_at DESC NULLS LAST;
END;
$$;

-- 9) Secure RPCs: create poll messages
CREATE OR REPLACE FUNCTION public.secure_create_group_poll_message(
    p_group_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_question TEXT DEFAULT '',
    p_options TEXT[] DEFAULT ARRAY[]::TEXT[],
    p_allows_multiple BOOLEAN DEFAULT FALSE
)
RETURNS public.group_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_poll_id UUID;
    v_inserted public.group_messages;
    v_question TEXT;
    v_opt TEXT;
    v_order INT := 0;
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

    v_question := LEFT(TRIM(COALESCE(p_question, '')), 300);
    IF v_question = '' THEN
        RAISE EXCEPTION 'POLL_QUESTION_REQUIRED';
    END IF;

    IF p_options IS NULL OR COALESCE(array_length(p_options, 1), 0) < 2 THEN
        RAISE EXCEPTION 'POLL_MIN_OPTIONS';
    END IF;

    IF COALESCE(array_length(p_options, 1), 0) > 12 THEN
        RAISE EXCEPTION 'POLL_MAX_OPTIONS';
    END IF;

    INSERT INTO public.message_polls (
        scope_type,
        scope_id,
        creator_id,
        question,
        allows_multiple
    ) VALUES (
        'group',
        p_group_id,
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

    IF (SELECT COUNT(*) FROM public.message_poll_options WHERE poll_id = v_poll_id) < 2 THEN
        RAISE EXCEPTION 'POLL_OPTIONS_INVALID';
    END IF;

    INSERT INTO public.group_messages (
        id,
        group_id,
        sender_id,
        content,
        content_type,
        poll_id,
        status
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_group_id,
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

CREATE OR REPLACE FUNCTION public.secure_create_community_poll_message(
    p_community_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_question TEXT DEFAULT '',
    p_options TEXT[] DEFAULT ARRAY[]::TEXT[],
    p_allows_multiple BOOLEAN DEFAULT FALSE
)
RETURNS public.community_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_poll_id UUID;
    v_inserted public.community_messages;
    v_question TEXT;
    v_opt TEXT;
    v_order INT := 0;
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

    v_question := LEFT(TRIM(COALESCE(p_question, '')), 300);
    IF v_question = '' THEN
        RAISE EXCEPTION 'POLL_QUESTION_REQUIRED';
    END IF;

    IF p_options IS NULL OR COALESCE(array_length(p_options, 1), 0) < 2 THEN
        RAISE EXCEPTION 'POLL_MIN_OPTIONS';
    END IF;

    IF COALESCE(array_length(p_options, 1), 0) > 12 THEN
        RAISE EXCEPTION 'POLL_MAX_OPTIONS';
    END IF;

    INSERT INTO public.message_polls (
        scope_type,
        scope_id,
        creator_id,
        question,
        allows_multiple
    ) VALUES (
        'community',
        p_community_id,
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

    IF (SELECT COUNT(*) FROM public.message_poll_options WHERE poll_id = v_poll_id) < 2 THEN
        RAISE EXCEPTION 'POLL_OPTIONS_INVALID';
    END IF;

    INSERT INTO public.community_messages (
        id,
        community_id,
        sender_id,
        content,
        content_type,
        poll_id
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_community_id,
        v_uid,
        v_question,
        'poll',
        v_poll_id
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

-- 10) Secure RPCs: vote + poll state
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
    ELSE
        RAISE EXCEPTION 'POLL_SCOPE_INVALID';
    END IF;

    RETURN QUERY
    WITH poll_base AS (
        SELECT id, allows_multiple, is_closed, question
        FROM public.message_polls
        WHERE id = p_poll_id
    ),
    total AS (
        SELECT COUNT(*)::BIGINT AS total_votes
        FROM public.message_poll_votes pv
        WHERE pv.poll_id = p_poll_id
    ),
    option_counts AS (
        SELECT
            o.id AS option_id,
            COUNT(v.option_id)::BIGINT AS vote_count
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
        COALESCE(oc.vote_count, 0)::BIGINT,
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
    LEFT JOIN option_counts oc ON oc.option_id = o.id
    WHERE o.poll_id = p_poll_id
    ORDER BY o.display_order ASC;
END;
$$;

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

-- 11) Grants
GRANT EXECUTE ON FUNCTION public.secure_mark_group_messages_read(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_mark_community_messages_read(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_get_group_message_receipts(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_get_community_message_receipts(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_create_group_poll_message(UUID, UUID, TEXT, TEXT[], BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_create_community_poll_message(UUID, UUID, TEXT, TEXT[], BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_get_poll_state(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_vote_poll_option(UUID, UUID, BOOLEAN) TO authenticated;

-- 12) Realtime publish (for optional live poll updates)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables
            WHERE pubname = 'supabase_realtime' AND tablename = 'group_message_receipts'
        ) THEN
            ALTER PUBLICATION supabase_realtime ADD TABLE public.group_message_receipts;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables
            WHERE pubname = 'supabase_realtime' AND tablename = 'community_message_receipts'
        ) THEN
            ALTER PUBLICATION supabase_realtime ADD TABLE public.community_message_receipts;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables
            WHERE pubname = 'supabase_realtime' AND tablename = 'message_poll_votes'
        ) THEN
            ALTER PUBLICATION supabase_realtime ADD TABLE public.message_poll_votes;
        END IF;
    END IF;
END $$;

NOTIFY pgrst, 'reload schema';

COMMIT;
