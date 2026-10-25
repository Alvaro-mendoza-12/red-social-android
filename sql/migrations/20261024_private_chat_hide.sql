-- Hide a private conversation for the authenticated user without deleting messages.
-- A newer message makes the conversation visible in that user's inbox again.

BEGIN;

ALTER TABLE public.chat_participants
  ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION public.secure_hide_chat(p_chat_id UUID)
RETURNS TABLE (chat_id UUID, user_id UUID, hidden_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_uid UUID := auth.uid();
  v_hidden_at TIMESTAMPTZ := NOW();
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
       AND COALESCE(c.is_group, FALSE) = FALSE
       AND v_uid::TEXT = ANY(c.participants)
  ) THEN
    RAISE EXCEPTION 'CHAT_ACCESS_DENIED';
  END IF;

  INSERT INTO public.chat_participants (chat_id, user_id, joined_at, hidden_at)
  VALUES (p_chat_id, v_uid, v_hidden_at, v_hidden_at)
  ON CONFLICT (chat_id, user_id)
  DO UPDATE SET hidden_at = EXCLUDED.hidden_at;

  RETURN QUERY
  SELECT cp.chat_id, cp.user_id, cp.hidden_at
    FROM public.chat_participants cp
   WHERE cp.chat_id = p_chat_id AND cp.user_id = v_uid;
END;
$$;

REVOKE ALL ON FUNCTION public.secure_hide_chat(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.secure_hide_chat(UUID) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
