-- ==========================================================
-- HOTFIX 2026: additional login emails globally unique
-- Ensures an additional email can never match:
-- 1) another user's primary email
-- 2) another user's additional login email
-- ==========================================================

BEGIN;

-- Normalize aliases already stored.
UPDATE public.profile_login_emails
   SET email = LOWER(TRIM(COALESCE(email, '')))
 WHERE email IS DISTINCT FROM LOWER(TRIM(COALESCE(email, '')));

-- Remove aliases that collide with a different user's primary email.
DELETE FROM public.profile_login_emails ple
USING public.profiles p
WHERE LOWER(TRIM(COALESCE(ple.email, ''))) = LOWER(TRIM(COALESCE(p.email, '')))
  AND ple.user_id <> p.id;

-- Keep uniqueness in aliases table.
CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_login_emails_unique_email
    ON public.profile_login_emails (email);

CREATE OR REPLACE FUNCTION public.enforce_profile_login_email_rules()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_primary_email TEXT;
    v_count INTEGER;
    v_conflict_user UUID;
BEGIN
    NEW.email := LOWER(TRIM(COALESCE(NEW.email, '')));

    IF NEW.email = '' THEN
        RAISE EXCEPTION 'INVALID_EMAIL';
    END IF;

    IF NEW.email !~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' THEN
        RAISE EXCEPTION 'INVALID_EMAIL';
    END IF;

    SELECT LOWER(TRIM(COALESCE(p.email, '')))
      INTO v_primary_email
      FROM public.profiles p
     WHERE p.id = NEW.user_id
     LIMIT 1;

    IF v_primary_email IS NOT NULL AND NEW.email = v_primary_email THEN
        RAISE EXCEPTION 'PRIMARY_EMAIL_NOT_ALLOWED';
    END IF;

    -- Block if this email is primary email in another account.
    SELECT p.id
      INTO v_conflict_user
      FROM public.profiles p
     WHERE LOWER(TRIM(COALESCE(p.email, ''))) = NEW.email
       AND p.id <> NEW.user_id
     LIMIT 1;

    IF v_conflict_user IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23505', MESSAGE = 'EMAIL_ALREADY_IN_USE';
    END IF;

    -- Block if this email is additional alias in another account.
    SELECT ple.user_id
      INTO v_conflict_user
      FROM public.profile_login_emails ple
     WHERE ple.email = NEW.email
       AND ple.user_id <> NEW.user_id
     LIMIT 1;

    IF v_conflict_user IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23505', MESSAGE = 'EMAIL_ALREADY_IN_USE';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT COUNT(*)
          INTO v_count
          FROM public.profile_login_emails ple
         WHERE ple.user_id = NEW.user_id;

        IF v_count >= 3 THEN
            RAISE EXCEPTION 'MAX_ADDITIONAL_EMAILS_REACHED';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_profile_login_email_rules ON public.profile_login_emails;
CREATE TRIGGER trg_profile_login_email_rules
BEFORE INSERT OR UPDATE OF email ON public.profile_login_emails
FOR EACH ROW
EXECUTE FUNCTION public.enforce_profile_login_email_rules();

NOTIFY pgrst, 'reload schema';

COMMIT;
