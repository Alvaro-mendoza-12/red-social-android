-- ==========================================================
-- HOTFIX 2026: Backend-driven account suspension hardening
-- - Canonical suspension_end_date support (keeps suspended_until compatibility)
-- - Backend suspension gate with automatic lift on expiration
-- - Session/token invalidation best effort
-- - Backend notification emission (account_suspended/account_enabled)
-- ==========================================================

BEGIN;

-- Required when an existing function has a different RETURNS TABLE shape.
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT);
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER);
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT, INTEGER, TEXT, BOOLEAN);
DROP FUNCTION IF EXISTS public.secure_get_my_suspension_state();

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspension_end_date TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspension_reason TEXT,
    ADD COLUMN IF NOT EXISTS suspension_count INTEGER NOT NULL DEFAULT 0;

UPDATE public.profiles
SET suspension_end_date = suspended_until
WHERE suspension_end_date IS NULL
  AND suspended_until IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_profiles_suspension_state
    ON public.profiles (is_suspended, COALESCE(suspension_end_date, suspended_until) DESC);

CREATE OR REPLACE FUNCTION public.sync_profile_suspension_dates()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Keep both columns in sync for backward compatibility.
    IF NEW.suspension_end_date IS NOT NULL THEN
        NEW.suspended_until := NEW.suspension_end_date;
    ELSE
        NEW.suspension_end_date := NEW.suspended_until;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_profile_suspension_dates ON public.profiles;
CREATE TRIGGER trg_sync_profile_suspension_dates
BEFORE INSERT OR UPDATE OF suspended_until, suspension_end_date
ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.sync_profile_suspension_dates();

CREATE OR REPLACE FUNCTION public.secure_get_my_suspension_state()
RETURNS TABLE (
    is_suspended BOOLEAN,
    suspended_until TIMESTAMPTZ,
    suspension_end_date TIMESTAMPTZ,
    suspension_reason TEXT,
    suspension_count INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_rows_lifted INTEGER := 0;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    -- Automatic lift when suspension expired.
    UPDATE public.profiles p
    SET
        is_suspended = false,
        suspended_until = NULL,
        suspension_end_date = NULL,
        suspension_reason = NULL,
        updated_at = NOW()
    WHERE p.id = v_uid
      AND COALESCE(p.is_suspended, false) = true
      AND COALESCE(p.suspension_end_date, p.suspended_until) IS NOT NULL
      AND COALESCE(p.suspension_end_date, p.suspended_until) <= NOW();

    GET DIAGNOSTICS v_rows_lifted = ROW_COUNT;

    IF v_rows_lifted > 0 THEN
        BEGIN
            INSERT INTO public.notifications (
                recipient_id,
                actor_id,
                type,
                content,
                extra_data
            )
            VALUES (
                v_uid,
                v_uid,
                'account_enabled',
                'Tu cuenta ha sido habilitada nuevamente.',
                jsonb_build_object('notification_type', 'account_enabled')
            );
        EXCEPTION
            WHEN undefined_table THEN
                NULL;
            WHEN undefined_column THEN
                BEGIN
                    INSERT INTO public.notifications (
                        recipient_id,
                        actor_id,
                        type,
                        content
                    )
                    VALUES (
                        v_uid,
                        v_uid,
                        'message',
                        'Tu cuenta ha sido habilitada nuevamente.'
                    );
                EXCEPTION WHEN OTHERS THEN
                    NULL;
                END;
            WHEN check_violation THEN
                BEGIN
                    INSERT INTO public.notifications (
                        recipient_id,
                        actor_id,
                        type,
                        content,
                        extra_data
                    )
                    VALUES (
                        v_uid,
                        v_uid,
                        'message',
                        'Tu cuenta ha sido habilitada nuevamente.',
                        jsonb_build_object('notification_type', 'account_enabled')
                    );
                EXCEPTION
                    WHEN undefined_column THEN
                        INSERT INTO public.notifications (
                            recipient_id,
                            actor_id,
                            type,
                            content
                        )
                        VALUES (
                            v_uid,
                            v_uid,
                            'message',
                            'Tu cuenta ha sido habilitada nuevamente.'
                        );
                    WHEN OTHERS THEN
                        NULL;
                END;
            WHEN OTHERS THEN
                NULL;
        END;
    END IF;

    RETURN QUERY
    SELECT
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        COALESCE(p.suspension_end_date, p.suspended_until),
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = v_uid
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_admin_set_user_suspension(
    p_user_id UUID,
    p_suspend BOOLEAN DEFAULT true,
    p_days INTEGER DEFAULT 7,
    p_reason TEXT DEFAULT NULL,
    p_duration_value INTEGER DEFAULT NULL,
    p_duration_unit TEXT DEFAULT 'days',
    p_permanent BOOLEAN DEFAULT false
)
RETURNS TABLE (
    user_id UUID,
    is_suspended BOOLEAN,
    suspended_until TIMESTAMPTZ,
    suspension_end_date TIMESTAMPTZ,
    suspension_reason TEXT,
    suspension_count INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_is_admin BOOLEAN := false;
    v_until TIMESTAMPTZ;
    v_reason TEXT := NULLIF(trim(COALESCE(p_reason, '')), '');
    v_duration_value INTEGER := GREATEST(COALESCE(p_duration_value, p_days, 7), 1);
    v_duration_unit TEXT := lower(trim(COALESCE(p_duration_unit, 'days')));
    v_permanent BOOLEAN := COALESCE(p_permanent, false);
    v_notification_type TEXT;
    v_notification_content TEXT;
    v_until_iso TEXT;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT
        COALESCE(p.is_admin, false)
        OR lower(trim(COALESCE(p.email, ''))) IN (
            'gamergamerff017@gmail.com',
            'alvaromendozamarin004@gmail.com'
        )
    INTO v_is_admin
    FROM public.profiles p
    WHERE p.id = v_actor
    LIMIT 1;

    IF NOT COALESCE(v_is_admin, false) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'TARGET_USER_REQUIRED';
    END IF;

    IF COALESCE(p_suspend, true) THEN
        IF v_duration_unit NOT IN ('seconds', 'minutes', 'hours', 'days', 'years') THEN
            v_duration_unit := 'days';
        END IF;

        IF v_permanent THEN
            v_until := NULL;
        ELSE
            v_until := CASE v_duration_unit
                WHEN 'seconds' THEN NOW() + make_interval(secs => v_duration_value)
                WHEN 'minutes' THEN NOW() + make_interval(mins => v_duration_value)
                WHEN 'hours' THEN NOW() + make_interval(hours => v_duration_value)
                WHEN 'years' THEN NOW() + make_interval(years => v_duration_value)
                ELSE NOW() + make_interval(days => v_duration_value)
            END;
        END IF;

        UPDATE public.profiles p
        SET
            is_suspended = true,
            suspended_until = v_until,
            suspension_end_date = v_until,
            suspension_reason = COALESCE(v_reason, 'Moderación administrativa'),
            suspension_count = GREATEST(COALESCE(p.suspension_count, 0), 0) + 1,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    ELSE
        UPDATE public.profiles p
        SET
            is_suspended = false,
            suspended_until = NULL,
            suspension_end_date = NULL,
            suspension_reason = NULL,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'TARGET_PROFILE_NOT_FOUND';
    END IF;

    -- Best effort: revoke refresh tokens and delete auth sessions.
    IF COALESCE(p_suspend, true) THEN
        BEGIN
            UPDATE auth.refresh_tokens rt
            SET
                revoked = true,
                updated_at = NOW()
            WHERE rt.user_id = p_user_id
              AND COALESCE(rt.revoked, false) = false;
        EXCEPTION
            WHEN undefined_table OR undefined_column OR insufficient_privilege THEN
                RAISE NOTICE 'Skipping refresh token revocation for %', p_user_id;
        END;

        BEGIN
            DELETE FROM auth.sessions s
            WHERE s.user_id = p_user_id;
        EXCEPTION
            WHEN undefined_table OR undefined_column OR insufficient_privilege THEN
                RAISE NOTICE 'Skipping auth session deletion for %', p_user_id;
        END;
    END IF;

    v_notification_type := CASE
        WHEN COALESCE(p_suspend, true) THEN 'account_suspended'
        ELSE 'account_enabled'
    END;

    v_until_iso := CASE
        WHEN v_until IS NULL THEN NULL
        ELSE to_char(v_until AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    END;

    v_notification_content := CASE
        WHEN COALESCE(p_suspend, true) AND v_permanent THEN
            'Tu cuenta fue suspendida permanentemente por el equipo de moderación.'
        WHEN COALESCE(p_suspend, true) AND v_until_iso IS NOT NULL AND v_reason IS NOT NULL THEN
            'Tu cuenta fue suspendida hasta ' || v_until_iso || '. Motivo: ' || v_reason || '.'
        WHEN COALESCE(p_suspend, true) AND v_until_iso IS NOT NULL THEN
            'Tu cuenta fue suspendida hasta ' || v_until_iso || ' por el equipo de moderación.'
        WHEN COALESCE(p_suspend, true) AND v_reason IS NOT NULL THEN
            'Tu cuenta fue suspendida temporalmente. Motivo: ' || v_reason || '.'
        WHEN COALESCE(p_suspend, true) THEN
            'Tu cuenta fue suspendida temporalmente por el equipo de moderación.'
        ELSE
            'Tu cuenta ha sido habilitada nuevamente.'
    END;

    -- Backend-emitted notification. Fall back to "message" if type constraint is strict.
    BEGIN
        INSERT INTO public.notifications (
            recipient_id,
            actor_id,
            type,
            content,
            extra_data
        )
        VALUES (
            p_user_id,
            v_actor,
            v_notification_type,
            v_notification_content,
            jsonb_strip_nulls(
                jsonb_build_object(
                    'notification_type', v_notification_type,
                    'suspended_until', v_until_iso,
                    'suspension_reason', v_reason,
                    'permanent', v_permanent,
                    'duration_unit', v_duration_unit,
                    'duration_value', v_duration_value
                )
            )
        );
    EXCEPTION
        WHEN undefined_table THEN
            NULL;
        WHEN undefined_column THEN
            BEGIN
                INSERT INTO public.notifications (
                    recipient_id,
                    actor_id,
                    type,
                    content
                )
                VALUES (
                    p_user_id,
                    v_actor,
                    'message',
                    v_notification_content
                );
            EXCEPTION WHEN OTHERS THEN
                NULL;
            END;
        WHEN check_violation THEN
            BEGIN
                INSERT INTO public.notifications (
                    recipient_id,
                    actor_id,
                    type,
                    content,
                    extra_data
                )
                VALUES (
                    p_user_id,
                    v_actor,
                    'message',
                    v_notification_content,
                    jsonb_strip_nulls(
                        jsonb_build_object(
                            'notification_type', v_notification_type,
                            'suspended_until', v_until_iso,
                            'suspension_reason', v_reason,
                            'permanent', v_permanent,
                            'duration_unit', v_duration_unit,
                            'duration_value', v_duration_value
                        )
                    )
                );
            EXCEPTION
                WHEN undefined_column THEN
                    INSERT INTO public.notifications (
                        recipient_id,
                        actor_id,
                        type,
                        content
                    )
                    VALUES (
                        p_user_id,
                        v_actor,
                        'message',
                        v_notification_content
                    );
                WHEN OTHERS THEN
                    NULL;
            END;
        WHEN OTHERS THEN
            NULL;
    END;

    RETURN QUERY
    SELECT
        p.id,
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        COALESCE(p.suspension_end_date, p.suspended_until),
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = p_user_id
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_get_my_suspension_state() TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT, INTEGER, TEXT, BOOLEAN) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
