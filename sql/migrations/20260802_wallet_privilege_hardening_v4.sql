-- P0 hardening for the legacy wallet surface.
--
-- Money mutations are server-authoritative. Authenticated mobile clients may
-- read their own wallet/history and the active catalog, but they must never be
-- able to UPDATE balances or call low-level credit/debit RPCs directly.

CREATE TABLE IF NOT EXISTS public.monetization_runtime_flags (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    live_gifts_enabled BOOLEAN NOT NULL DEFAULT false,
    coin_purchases_enabled BOOLEAN NOT NULL DEFAULT false,
    subscriptions_enabled BOOLEAN NOT NULL DEFAULT false,
    creator_payouts_enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keep this migration safe when a previous interrupted deployment created an
-- older version of the singleton table.
ALTER TABLE public.monetization_runtime_flags
    ADD COLUMN IF NOT EXISTS live_gifts_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS coin_purchases_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS subscriptions_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS creator_payouts_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

INSERT INTO public.monetization_runtime_flags(singleton)
VALUES (true)
ON CONFLICT (singleton) DO NOTHING;

ALTER TABLE public.monetization_runtime_flags ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.monetization_runtime_flags FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.monetization_runtime_flags TO service_role;

CREATE OR REPLACE FUNCTION public.enforce_gift_runtime_gate()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_enabled BOOLEAN := false;
BEGIN
    SELECT flags.live_gifts_enabled
      INTO v_enabled
      FROM public.monetization_runtime_flags AS flags
     WHERE flags.singleton = true;

    IF v_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION USING MESSAGE = 'LIVE_GIFTS_DISABLED', ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.enforce_gift_runtime_gate()
    FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS gift_transactions_runtime_gate ON public.gift_transactions;
CREATE TRIGGER gift_transactions_runtime_gate
    BEFORE INSERT ON public.gift_transactions
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_gift_runtime_gate();

DROP POLICY IF EXISTS "wallet_update_own" ON public.wallets;

ALTER TABLE public.wallets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.wallet_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gift_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.virtual_gifts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.creator_earnings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'wallets'
           AND policyname = 'wallet_select_own'
    ) THEN
        CREATE POLICY wallet_select_own
            ON public.wallets
            FOR SELECT
            TO authenticated
            USING (user_id = auth.uid());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'wallet_transactions'
           AND policyname = 'wallet_transactions_select_own'
    ) THEN
        CREATE POLICY wallet_transactions_select_own
            ON public.wallet_transactions
            FOR SELECT
            TO authenticated
            USING (user_id = auth.uid());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'gift_transactions'
           AND policyname = 'gift_transactions_select_participant'
    ) THEN
        CREATE POLICY gift_transactions_select_participant
            ON public.gift_transactions
            FOR SELECT
            TO authenticated
            USING (sender_id = auth.uid() OR receiver_id = auth.uid());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'virtual_gifts'
           AND policyname = 'virtual_gifts_select_active'
    ) THEN
        CREATE POLICY virtual_gifts_select_active
            ON public.virtual_gifts
            FOR SELECT
            TO anon, authenticated
            USING (is_active = true);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'creator_earnings'
           AND policyname = 'creator_earnings_select_own'
    ) THEN
        CREATE POLICY creator_earnings_select_own
            ON public.creator_earnings
            FOR SELECT
            TO authenticated
            USING (creator_id = auth.uid());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'user_subscriptions'
           AND policyname = 'user_subscriptions_select_own'
    ) THEN
        CREATE POLICY user_subscriptions_select_own
            ON public.user_subscriptions
            FOR SELECT
            TO authenticated
            USING (user_id = auth.uid());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'subscription_plans'
           AND policyname = 'subscription_plans_select_active'
    ) THEN
        CREATE POLICY subscription_plans_select_active
            ON public.subscription_plans
            FOR SELECT
            TO anon, authenticated
            USING (is_active = true);
    END IF;
END $$;

REVOKE ALL ON TABLE public.wallets FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.wallet_transactions FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.gift_transactions FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.virtual_gifts FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.creator_earnings FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.user_subscriptions FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.subscription_plans FROM PUBLIC, anon, authenticated;

GRANT SELECT ON TABLE public.wallets TO authenticated;
GRANT SELECT ON TABLE public.wallet_transactions TO authenticated;
GRANT SELECT ON TABLE public.gift_transactions TO authenticated;
GRANT SELECT ON TABLE public.virtual_gifts TO anon, authenticated;
GRANT SELECT ON TABLE public.creator_earnings TO authenticated;
GRANT SELECT ON TABLE public.user_subscriptions TO authenticated;
GRANT SELECT ON TABLE public.subscription_plans TO anon, authenticated;

-- Supabase's backend role remains the only role allowed to mutate these
-- tables directly. SECURITY DEFINER functions still apply their own checks.
GRANT ALL ON TABLE public.wallets TO service_role;
GRANT ALL ON TABLE public.wallet_transactions TO service_role;
GRANT ALL ON TABLE public.gift_transactions TO service_role;
GRANT ALL ON TABLE public.virtual_gifts TO service_role;
GRANT ALL ON TABLE public.creator_earnings TO service_role;
GRANT ALL ON TABLE public.user_subscriptions TO service_role;
GRANT ALL ON TABLE public.subscription_plans TO service_role;

-- Reject new corrupt balances/prices immediately without making deployment
-- depend on validating historical imports in the same release.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'wallets_nonnegative_v4') THEN
        ALTER TABLE public.wallets
            ADD CONSTRAINT wallets_nonnegative_v4
            CHECK (balance_diamonds >= 0 AND balance_credits >= 0 AND total_spent >= 0)
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'virtual_gifts_positive_price_v4') THEN
        ALTER TABLE public.virtual_gifts
            ADD CONSTRAINT virtual_gifts_positive_price_v4
            CHECK (price_diamonds > 0 AND price_credits >= 0)
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'gift_transactions_positive_amount_v4') THEN
        ALTER TABLE public.gift_transactions
            ADD CONSTRAINT gift_transactions_positive_amount_v4
            CHECK (amount_diamonds > 0 AND amount_credits >= 0)
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'wallet_transactions_nonnegative_balance_v4') THEN
        ALTER TABLE public.wallet_transactions
            ADD CONSTRAINT wallet_transactions_nonnegative_balance_v4
            CHECK (balance_after_diamonds >= 0 AND balance_after_credits >= 0)
            NOT VALID;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION public.wallet_add_diamonds(
    p_user_id UUID,
    p_amount INT,
    p_source TEXT DEFAULT 'purchase'
)
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_balance INT;
    v_credits DECIMAL(10,2);
    v_total_spent DECIMAL(10,2);
BEGIN
    IF p_user_id IS NULL OR p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION USING MESSAGE = 'WALLET_CREDIT_INVALID', ERRCODE = '22023';
    END IF;
    IF lower(COALESCE(p_source, '')) IN ('purchase', 'google_play', 'play_purchase')
       AND NOT COALESCE((
           SELECT flags.coin_purchases_enabled
             FROM public.monetization_runtime_flags AS flags
            WHERE flags.singleton = true
       ), false) THEN
        RAISE EXCEPTION USING MESSAGE = 'COIN_PURCHASES_DISABLED', ERRCODE = '42501';
    END IF;

    INSERT INTO public.wallets(user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, p_amount, 0, 0)
    ON CONFLICT (user_id) DO UPDATE
       SET balance_diamonds = wallets.balance_diamonds + EXCLUDED.balance_diamonds,
           updated_at = now()
    RETURNING wallets.balance_diamonds,
              wallets.balance_credits,
              wallets.total_spent
         INTO v_balance, v_credits, v_total_spent;

    INSERT INTO public.wallet_transactions(
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, description
    ) VALUES (
        p_user_id, 'deposit', p_amount, v_balance, v_credits,
        'Crédito verificado: ' || left(COALESCE(NULLIF(btrim(p_source), ''), 'purchase'), 80)
    );

    RETURN QUERY SELECT p_user_id, v_balance, v_credits, v_total_spent;
END;
$$;

CREATE OR REPLACE FUNCTION public.wallet_spend_diamonds(
    p_user_id UUID,
    p_amount INT,
    p_purpose TEXT,
    p_reference_id UUID DEFAULT NULL
)
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_balance INT;
    v_credits DECIMAL(10,2);
    v_total_spent DECIMAL(10,2);
BEGIN
    IF p_user_id IS NULL OR p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION USING MESSAGE = 'WALLET_DEBIT_INVALID', ERRCODE = '22023';
    END IF;

    SELECT w.balance_diamonds, w.balance_credits, w.total_spent
      INTO v_balance, v_credits, v_total_spent
      FROM public.wallets AS w
     WHERE w.user_id = p_user_id
     FOR UPDATE;

    IF NOT FOUND OR v_balance < p_amount THEN
        RAISE EXCEPTION USING MESSAGE = 'INSUFFICIENT_DIAMONDS', ERRCODE = 'P0001';
    END IF;

    v_balance := v_balance - p_amount;
    UPDATE public.wallets AS w
       SET balance_diamonds = v_balance,
           updated_at = now()
     WHERE w.user_id = p_user_id;

    INSERT INTO public.wallet_transactions(
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, reference_id, description
    ) VALUES (
        p_user_id, 'purchase', -p_amount, v_balance, v_credits,
        p_reference_id,
        'Débito verificado: ' || left(COALESCE(NULLIF(btrim(p_purpose), ''), 'purchase'), 80)
    );

    RETURN QUERY SELECT p_user_id, v_balance, v_credits, v_total_spent;
END;
$$;

REVOKE ALL ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID) TO service_role;

-- The old helper exposes private sender data by arbitrary p_user_id and is not
-- used by the current client. Keep it backend-only until a paginated receipt
-- endpoint with an auth-bound identity replaces it.
REVOKE ALL ON FUNCTION public.get_user_received_gifts(UUID, INT, INT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_user_received_gifts(UUID, INT, INT) TO service_role;

-- Public leaderboards are not part of the launch surface yet.
REVOKE ALL ON FUNCTION public.get_live_stream_top_donors(UUID, INT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_live_stream_top_donors(UUID, INT) TO service_role;

-- This legacy subscription RPC still spends wallet coins. Bind its identity to
-- the caller so it cannot create a subscription for, or debit, another user.
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
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_actor_id UUID := auth.uid();
    v_plan_price DECIMAL(10,2);
    v_plan_name TEXT;
    v_subscription_id UUID;
    v_period_start TIMESTAMPTZ := now();
    v_period_end TIMESTAMPTZ := now() + INTERVAL '1 month';
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF p_user_id IS NULL OR p_user_id <> v_actor_id THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;
    IF NOT COALESCE((
        SELECT flags.subscriptions_enabled
          FROM public.monetization_runtime_flags AS flags
         WHERE flags.singleton = true
    ), false) THEN
        RAISE EXCEPTION USING MESSAGE = 'SUBSCRIPTIONS_DISABLED', ERRCODE = '42501';
    END IF;
    IF p_payment_method IS DISTINCT FROM 'wallet' THEN
        RAISE EXCEPTION USING MESSAGE = 'PAYMENT_METHOD_NOT_SUPPORTED', ERRCODE = '22023';
    END IF;

    SELECT sp.price_monthly, sp.name
      INTO v_plan_price, v_plan_name
      FROM public.subscription_plans AS sp
     WHERE sp.id = p_plan_id
       AND sp.is_active = true
     FOR SHARE;

    IF NOT FOUND OR v_plan_price <= 0 THEN
        RAISE EXCEPTION USING MESSAGE = 'PLAN_NOT_FOUND', ERRCODE = 'P0002';
    END IF;

    v_subscription_id := gen_random_uuid();
    PERFORM public.wallet_spend_diamonds(
        v_actor_id,
        (v_plan_price * 10)::INT,
        'suscripción_premium',
        v_subscription_id
    );

    INSERT INTO public.user_subscriptions(
        id, user_id, plan_id, status, current_period_start, current_period_end
    ) VALUES (
        v_subscription_id, v_actor_id, p_plan_id, 'active', v_period_start, v_period_end
    );

    RETURN QUERY SELECT
        v_subscription_id, v_plan_name, v_period_start, v_period_end;
END;
$$;

REVOKE ALL ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT) TO authenticated;
