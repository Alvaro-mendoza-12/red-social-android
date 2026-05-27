-- Sprint 4 Profile/Settings: secure self-service account deletion.
-- Safe to run multiple times from Supabase SQL editor.

CREATE OR REPLACE FUNCTION public.delete_own_account()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    -- Mark first so audit logs or failed cascades never leave the account looking active.
    IF to_regclass('public.profiles') IS NOT NULL THEN
        UPDATE public.profiles
        SET
            status = 'deactivated',
            is_private = true,
            profile_visibility = 'connections',
            last_seen_visibility = 'nobody',
            online_status_visibility = 'nobody',
            updated_at = now()
        WHERE id = v_user_id;
    END IF;

    IF to_regclass('public.profile_login_emails') IS NOT NULL THEN
        DELETE FROM public.profile_login_emails
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.gift_transactions') IS NOT NULL THEN
        DELETE FROM public.gift_transactions
        WHERE sender_id = v_user_id OR receiver_id = v_user_id;
    END IF;

    IF to_regclass('public.wallet_transactions') IS NOT NULL THEN
        DELETE FROM public.wallet_transactions
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.ad_impressions') IS NOT NULL
       AND to_regclass('public.ad_campaigns') IS NOT NULL THEN
        DELETE FROM public.ad_impressions
        WHERE campaign_id IN (
            SELECT id FROM public.ad_campaigns WHERE advertiser_id = v_user_id
        );
    END IF;

    IF to_regclass('public.ad_impressions') IS NOT NULL THEN
        UPDATE public.ad_impressions
        SET user_id = NULL
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.ad_campaigns') IS NOT NULL THEN
        DELETE FROM public.ad_campaigns
        WHERE advertiser_id = v_user_id;
    END IF;

    -- Deleting auth.users cascades profiles and profile-backed social data through the schema.
    DELETE FROM auth.users
    WHERE id = v_user_id;
END;
$$;

REVOKE ALL ON FUNCTION public.delete_own_account() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_own_account() TO authenticated;
