-- ==========================================================
-- HOTFIX: Additional login emails (up to 3 per user)
-- Allows login with secondary emails mapped to primary profile email.
-- ==========================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.profile_login_emails (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, email)
);

UPDATE public.profile_login_emails
   SET email = LOWER(TRIM(email))
 WHERE email <> LOWER(TRIM(email));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_login_emails_unique_email
    ON public.profile_login_emails (email);

CREATE INDEX IF NOT EXISTS idx_profile_login_emails_user_created
    ON public.profile_login_emails (user_id, created_at DESC);

ALTER TABLE public.profile_login_emails ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'profile_login_emails'
          AND policyname = 'profile_login_emails_select_own'
    ) THEN
        CREATE POLICY profile_login_emails_select_own
            ON public.profile_login_emails
            FOR SELECT
            USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'profile_login_emails'
          AND policyname = 'profile_login_emails_insert_own'
    ) THEN
        CREATE POLICY profile_login_emails_insert_own
            ON public.profile_login_emails
            FOR INSERT
            WITH CHECK (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'profile_login_emails'
          AND policyname = 'profile_login_emails_delete_own'
    ) THEN
        CREATE POLICY profile_login_emails_delete_own
            ON public.profile_login_emails
            FOR DELETE
            USING (auth.uid() = user_id);
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_profile_login_email_rules()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_primary_email TEXT;
    v_count INTEGER;
BEGIN
    NEW.email := LOWER(TRIM(COALESCE(NEW.email, '')));

    IF NEW.email = '' THEN
        RAISE EXCEPTION 'INVALID_EMAIL';
    END IF;

    IF NEW.email !~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' THEN
        RAISE EXCEPTION 'INVALID_EMAIL';
    END IF;

    SELECT LOWER(TRIM(p.email))
      INTO v_primary_email
      FROM public.profiles p
     WHERE p.id = NEW.user_id
     LIMIT 1;

    IF v_primary_email IS NOT NULL AND NEW.email = v_primary_email THEN
        RAISE EXCEPTION 'PRIMARY_EMAIL_NOT_ALLOWED';
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

CREATE OR REPLACE FUNCTION public.resolve_login_email(p_email TEXT)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_lookup TEXT := LOWER(TRIM(COALESCE(p_email, '')));
    v_user_id UUID;
    v_primary_email TEXT;
BEGIN
    IF COALESCE(auth.role(), 'anon') NOT IN ('anon', 'authenticated', 'service_role') THEN
        RAISE EXCEPTION 'FORBIDDEN';
    END IF;

    IF v_lookup = '' THEN
        RETURN NULL;
    END IF;

    SELECT ple.user_id
      INTO v_user_id
      FROM public.profile_login_emails ple
     WHERE ple.email = v_lookup
     LIMIT 1;

    IF v_user_id IS NULL THEN
        RETURN v_lookup;
    END IF;

    SELECT LOWER(TRIM(p.email))
      INTO v_primary_email
      FROM public.profiles p
     WHERE p.id = v_user_id
     LIMIT 1;

    IF v_primary_email IS NULL OR v_primary_email = '' THEN
        RETURN v_lookup;
    END IF;

    RETURN v_primary_email;
END;
$$;

GRANT EXECUTE ON FUNCTION public.resolve_login_email(TEXT) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
