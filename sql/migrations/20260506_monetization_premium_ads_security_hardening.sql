-- ============================================
-- Premium & Ads Security Hardening
-- FECHA: 2026-05-06
-- PURPOSE: Activar monetizacion desde cliente sin exponer suscripciones, ads ni pagos.
-- ============================================

ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ad_campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ad_impressions ENABLE ROW LEVEL SECURITY;

GRANT SELECT ON public.subscription_plans TO authenticated;
GRANT SELECT ON public.user_subscriptions TO authenticated;
GRANT SELECT ON public.ad_campaigns TO authenticated;
GRANT SELECT, INSERT ON public.ad_impressions TO authenticated;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'user_subscriptions_user_id_plan_id_status_key'
    ) THEN
        ALTER TABLE public.user_subscriptions
            DROP CONSTRAINT user_subscriptions_user_id_plan_id_status_key;
    END IF;
END $$;

WITH ranked_active_subscriptions AS (
    SELECT
        id,
        row_number() OVER (
            PARTITION BY user_id
            ORDER BY current_period_end DESC, created_at DESC, id DESC
        ) AS row_rank
    FROM public.user_subscriptions
    WHERE status = 'active'
)
UPDATE public.user_subscriptions us
SET status = 'canceled',
    cancel_at_period_end = TRUE,
    updated_at = now()
FROM ranked_active_subscriptions ranked
WHERE us.id = ranked.id
  AND ranked.row_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_subscriptions_one_active_per_user
    ON public.user_subscriptions(user_id)
    WHERE status = 'active';

DROP POLICY IF EXISTS subscription_plans_active_select_policy ON public.subscription_plans;
CREATE POLICY subscription_plans_active_select_policy ON public.subscription_plans
FOR SELECT TO authenticated
USING (
    is_active = TRUE OR
    EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    )
);

DROP POLICY IF EXISTS user_subscriptions_owner_select_policy ON public.user_subscriptions;
CREATE POLICY user_subscriptions_owner_select_policy ON public.user_subscriptions
FOR SELECT TO authenticated
USING (
    user_id = auth.uid() OR
    EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    )
);

DROP POLICY IF EXISTS ad_campaigns_active_select_policy ON public.ad_campaigns;
CREATE POLICY ad_campaigns_active_select_policy ON public.ad_campaigns
FOR SELECT TO authenticated
USING (
    (
        status = 'active'
        AND start_date <= now()
        AND end_date >= now()
    ) OR
    advertiser_id = auth.uid() OR
    EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    )
);

DROP POLICY IF EXISTS ad_impressions_owner_select_policy ON public.ad_impressions;
CREATE POLICY ad_impressions_owner_select_policy ON public.ad_impressions
FOR SELECT TO authenticated
USING (
    user_id = auth.uid() OR
    EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    )
);

DROP POLICY IF EXISTS ad_impressions_insert_self_policy ON public.ad_impressions;
CREATE POLICY ad_impressions_insert_self_policy ON public.ad_impressions
FOR INSERT TO authenticated
WITH CHECK (
    user_id = auth.uid()
    AND EXISTS (
        SELECT 1
        FROM public.ad_campaigns c
        WHERE c.id = campaign_id
          AND c.status = 'active'
          AND c.start_date <= now()
          AND c.end_date >= now()
    )
);

CREATE OR REPLACE FUNCTION public.subscribe_to_plan(
    p_user_id UUID,
    p_plan_id UUID,
    p_payment_method TEXT DEFAULT 'wallet'
)
RETURNS TABLE (
    subscription_id UUID,
    plan_name TEXT,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ
) AS $$
DECLARE
    selected_plan_price DECIMAL(10,2);
    selected_plan_name TEXT;
    sub_id UUID;
    period_start TIMESTAMPTZ;
    period_end TIMESTAMPTZ;
    diamonds_cost INT;
    normalized_payment_method TEXT := COALESCE(NULLIF(trim(p_payment_method), ''), 'wallet');
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL OR p_plan_id IS NULL THEN
        RAISE EXCEPTION 'User and plan are required';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'Users can only subscribe their own account';
    END IF;

    IF normalized_payment_method <> 'wallet'
       AND COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin THEN
        RAISE EXCEPTION 'Only wallet payments are available from the client';
    END IF;

    SELECT sp.price_monthly, sp.name
    INTO selected_plan_price, selected_plan_name
    FROM public.subscription_plans sp
    WHERE sp.id = p_plan_id
      AND sp.is_active = TRUE;

    IF selected_plan_price IS NULL THEN
        RAISE EXCEPTION 'Plan no encontrado o inactivo';
    END IF;

    period_start := now();
    period_end := period_start + INTERVAL '1 month';
    diamonds_cost := CEIL(selected_plan_price * 10)::INT;

    IF normalized_payment_method = 'wallet' AND diamonds_cost > 0 THEN
        PERFORM public.wallet_spend_diamonds(
            p_user_id,
            diamonds_cost,
            'suscripcion_premium',
            p_plan_id
        );
    END IF;

    SELECT us.id
    INTO sub_id
    FROM public.user_subscriptions us
    WHERE us.user_id = p_user_id
      AND us.plan_id = p_plan_id
      AND us.status = 'active'
    ORDER BY us.current_period_end DESC
    LIMIT 1
    FOR UPDATE;

    IF sub_id IS NOT NULL THEN
        UPDATE public.user_subscriptions
        SET current_period_start = period_start,
            current_period_end = period_end,
            cancel_at_period_end = FALSE,
            updated_at = now()
        WHERE id = sub_id;

        RETURN QUERY SELECT sub_id, selected_plan_name, period_start, period_end;
        RETURN;
    END IF;

    UPDATE public.user_subscriptions
    SET status = 'canceled',
        cancel_at_period_end = TRUE,
        updated_at = now()
    WHERE user_id = p_user_id
      AND status = 'active';

    INSERT INTO public.user_subscriptions (
        user_id,
        plan_id,
        status,
        current_period_start,
        current_period_end,
        cancel_at_period_end,
        created_at,
        updated_at
    ) VALUES (
        p_user_id,
        p_plan_id,
        'active',
        period_start,
        period_end,
        FALSE,
        now(),
        now()
    )
    RETURNING id INTO sub_id;

    RETURN QUERY SELECT sub_id, selected_plan_name, period_start, period_end;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT) TO authenticated, service_role;
