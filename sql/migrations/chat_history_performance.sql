-- Supporting indexes for the chronological keyset pages used by chat screens.
-- Safe to apply repeatedly; no table data is rewritten.
CREATE INDEX IF NOT EXISTS idx_messages_chat_created_id
    ON public.messages (chat_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_group_messages_group_created_id
    ON public.group_messages (group_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_community_messages_community_created_id
    ON public.community_messages (community_id, created_at DESC, id DESC);
