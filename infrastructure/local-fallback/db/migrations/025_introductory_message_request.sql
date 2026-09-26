-- Existing chats retain NULL to preserve established conversations.
ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS intro_policy_started_at TIMESTAMPTZ;
ALTER TABLE fallback_chats ALTER COLUMN intro_policy_started_at SET DEFAULT NOW();

CREATE OR REPLACE FUNCTION fallback_guard_introductory_message()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  recipient TEXT;
  started_at TIMESTAMPTZ;
  sender_name TEXT;
  request_id UUID;
  imported_friendship BOOLEAN;
BEGIN
  SELECT chat.intro_policy_started_at,
         (SELECT participant FROM unnest(chat.participants) AS participant
          WHERE lower(participant) <> lower(NEW.sender_id) LIMIT 1)
  INTO started_at, recipient
  FROM fallback_chats AS chat
  WHERE lower(chat.id::text) = lower(NEW.chat_id) AND chat.is_group = FALSE;
  IF started_at IS NULL OR recipient IS NULL THEN RETURN NEW; END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended(lower(NEW.chat_id) || ':' || lower(NEW.sender_id), 0));
  IF EXISTS (SELECT 1 FROM fallback_messages WHERE id = NEW.id) THEN RETURN NEW; END IF;

  IF EXISTS (
    SELECT 1 FROM fallback_user_blocks
    WHERE (lower(blocker_id) = lower(recipient) AND lower(blocked_id) = lower(NEW.sender_id))
       OR (lower(blocker_id) = lower(NEW.sender_id) AND lower(blocked_id) = lower(recipient))
  ) THEN RAISE EXCEPTION 'CHAT_BLOCKED_BY_RECIPIENT'; END IF;

  IF EXISTS (
    SELECT 1 FROM fallback_friends
    WHERE status = 'accepted'
      AND ((lower(sender_id) = lower(NEW.sender_id) AND lower(receiver_id) = lower(recipient))
        OR (lower(receiver_id) = lower(NEW.sender_id) AND lower(sender_id) = lower(recipient)))
  ) THEN RETURN NEW; END IF;

  IF EXISTS (
    SELECT 1 FROM fallback_compat_rows
    WHERE table_name = 'friends' AND lower(row->>'status') = 'accepted'
      AND ((lower(row->>'sender_id') = lower(NEW.sender_id)
            AND lower(row->>'receiver_id') = lower(recipient))
        OR (lower(row->>'receiver_id') = lower(NEW.sender_id)
            AND lower(row->>'sender_id') = lower(recipient)))
  ) THEN RETURN NEW; END IF;
  IF to_regclass('supabase_import.friends') IS NOT NULL THEN
    EXECUTE
      'SELECT EXISTS (SELECT 1 FROM supabase_import.friends AS friend
       WHERE lower(COALESCE(to_jsonb(friend)->>''status'', '''')) = ''accepted''
         AND ((lower(COALESCE(to_jsonb(friend)->>''sender_id'', '''')) = lower($1)
               AND lower(COALESCE(to_jsonb(friend)->>''receiver_id'', '''')) = lower($2))
           OR (lower(COALESCE(to_jsonb(friend)->>''receiver_id'', '''')) = lower($1)
               AND lower(COALESCE(to_jsonb(friend)->>''sender_id'', '''')) = lower($2))))'
    INTO imported_friendship USING NEW.sender_id, recipient;
    IF imported_friendship THEN RETURN NEW; END IF;
  END IF;

  IF EXISTS (
    SELECT 1 FROM fallback_friends
    WHERE status = 'rejected'
      AND lower(sender_id) = lower(NEW.sender_id)
      AND lower(receiver_id) = lower(recipient)
  ) THEN RAISE EXCEPTION 'FRIEND_REQUEST_REJECTED'; END IF;

  IF EXISTS (
    SELECT 1 FROM fallback_messages
    WHERE lower(chat_id) = lower(NEW.chat_id)
      AND lower(sender_id) = lower(NEW.sender_id)
  ) THEN RAISE EXCEPTION 'FRIEND_REQUEST_PENDING'; END IF;

  INSERT INTO fallback_friends(sender_id, receiver_id, status)
  VALUES (NEW.sender_id, recipient, 'pending')
  ON CONFLICT (sender_id, receiver_id) DO NOTHING
  RETURNING id INTO request_id;

  IF request_id IS NOT NULL THEN
    SELECT COALESCE(NULLIF(display_name, ''), split_part(email::text, '@', 1), 'Usuario')
    INTO sender_name FROM fallback_users WHERE lower(id::text) = lower(NEW.sender_id) LIMIT 1;
    INSERT INTO fallback_notifications(
      recipient_id, actor_id, actor_name, type, target_id, target_type, content, extra_data
    ) VALUES (
      recipient, NEW.sender_id, COALESCE(sender_name, 'Usuario'),
      'friend_request', NEW.sender_id, 'profile', 'Te envió una solicitud de amistad.',
      jsonb_build_object('request_id', request_id::text, 'chat_id', NEW.chat_id)
    );
  END IF;
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_fallback_guard_introductory_message ON fallback_messages;
CREATE TRIGGER trg_fallback_guard_introductory_message
BEFORE INSERT ON fallback_messages
FOR EACH ROW EXECUTE FUNCTION fallback_guard_introductory_message();
