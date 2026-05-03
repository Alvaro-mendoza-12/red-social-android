-- ==========================================================
-- HOTFIX 2026: Secure notifications read mutation RPC
-- - Adds a backend-enforced path to mark notifications as read
-- - Prevents cross-user mutation attempts even with SECURITY DEFINER
-- - Optional rate-limit guard when guard_rate_limit exists
-- ==========================================================

BEGIN;

CREATE OR REPLACE FUNCTION public.secure_mark_notifications_read(
    p_notification_ids UUID[] DEFAULT NULL,
    p_mark_all BOOLEAN DEFAULT FALSE
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_processed INTEGER := 0;
    v_guard RECORD;
    v_ids_len INTEGER := COALESCE(array_length(p_notification_ids, 1), 0);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF to_regprocedure('public.guard_rate_limit(uuid,text,text,integer,integer,integer)') IS NOT NULL THEN
        SELECT * INTO v_guard
          FROM public.guard_rate_limit(v_uid, 'notifications_read', 'notifications', 180, 60, 90);
        IF v_guard.allowed IS NOT TRUE THEN
            RAISE EXCEPTION 'RATE_LIMIT_NOTIFICATIONS_READ:%', COALESCE(v_guard.retry_after_ms, 0);
        END IF;
    END IF;

    IF p_mark_all IS TRUE THEN
        UPDATE public.notifications
           SET is_read = TRUE
         WHERE recipient_id = v_uid
           AND COALESCE(is_read, FALSE) = FALSE;
    ELSE
        IF v_ids_len <= 0 THEN
            RAISE EXCEPTION 'NOTIFICATION_IDS_REQUIRED';
        END IF;

        UPDATE public.notifications
           SET is_read = TRUE
         WHERE recipient_id = v_uid
           AND id = ANY(p_notification_ids)
           AND COALESCE(is_read, FALSE) = FALSE;
    END IF;

    GET DIAGNOSTICS v_processed = ROW_COUNT;
    RETURN v_processed;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_mark_notifications_read(UUID[], BOOLEAN) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
