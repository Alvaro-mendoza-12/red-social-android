-- Coin purchase ledger and catalog (feature/live-gifts)
--
-- Google Play is the price/entitlement authority.  This migration deliberately
-- stores no client supplied price and never accepts a purchase token as proof
-- of payment.  A trusted verifier (Edge Function or the local fallback) must
-- call credit_verified_google_play_purchase only after Google Play reports a
-- completed purchase for this exact product and account.

CREATE TABLE IF NOT EXISTS public.billing_coin_products (
    product_id TEXT PRIMARY KEY,
    coins INT NOT NULL CHECK (coins > 0 AND coins <= 1000000),
    sort_order INT NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- These identifiers are only a launch catalog.  Google Play remains the
-- source of the localized price; operations may replace the product ids
-- before enabling coin_purchases_enabled.
INSERT INTO public.billing_coin_products(product_id, coins, sort_order)
VALUES
    ('novvin_coins_70', 70, 1),
    ('novvin_coins_140', 140, 2),
    ('novvin_coins_350', 350, 3),
    ('novvin_coins_700', 700, 4),
    ('novvin_coins_1400', 1400, 5),
    ('novvin_coins_3500', 3500, 6),
    ('novvin_coins_7000', 7000, 7),
    ('novvin_coins_14000', 14000, 8)
ON CONFLICT (product_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.billing_purchases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    product_id TEXT NOT NULL REFERENCES public.billing_coin_products(product_id),
    purchase_token_hash TEXT NOT NULL,
    order_id TEXT,
    coins INT NOT NULL CHECK (coins > 0),
    status TEXT NOT NULL DEFAULT 'credited'
        CHECK (status IN ('credited', 'pending', 'refunded', 'revoked', 'failed')),
    consumed BOOLEAN NOT NULL DEFAULT false,
    purchased_at TIMESTAMPTZ,
    credited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    provider_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT billing_purchases_token_hash_format
        CHECK (purchase_token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS billing_purchases_token_hash_uidx
    ON public.billing_purchases(purchase_token_hash);
CREATE INDEX IF NOT EXISTS billing_purchases_user_created_idx
    ON public.billing_purchases(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS billing_purchases_order_idx
    ON public.billing_purchases(order_id)
    WHERE order_id IS NOT NULL;

ALTER TABLE public.billing_coin_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.billing_purchases ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.billing_coin_products FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.billing_purchases FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.billing_coin_products TO anon, authenticated;
GRANT SELECT ON TABLE public.billing_purchases TO authenticated;
GRANT ALL ON TABLE public.billing_coin_products TO service_role;
GRANT ALL ON TABLE public.billing_purchases TO service_role;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'billing_coin_products'
           AND policyname = 'billing_coin_products_select_active'
    ) THEN
        CREATE POLICY billing_coin_products_select_active
            ON public.billing_coin_products
            FOR SELECT
            TO anon, authenticated
            USING (is_active = true);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename = 'billing_purchases'
           AND policyname = 'billing_purchases_select_own'
    ) THEN
        CREATE POLICY billing_purchases_select_own
            ON public.billing_purchases
            FOR SELECT
            TO authenticated
            USING (user_id = auth.uid());
    END IF;
END $$;

-- The mobile client uses this read-only RPC to discover the catalog.  It
-- remains disabled unless the runtime flag is enabled and six-to-eight
-- active products are configured, matching the fallback safety gate.
CREATE OR REPLACE FUNCTION public.billing_capabilities()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_enabled BOOLEAN := false;
    v_products JSONB := '[]'::jsonb;
    v_count INT := 0;
BEGIN
    SELECT COUNT(*) FILTER (WHERE is_active)::INT,
           COALESCE(
               jsonb_agg(
                   jsonb_build_object(
                       'product_id', product_id,
                       'coins', coins
                   ) ORDER BY sort_order, product_id
               ) FILTER (WHERE is_active),
               '[]'::jsonb
           )
      INTO v_count, v_products
      FROM public.billing_coin_products;

    SELECT COALESCE(flags.coin_purchases_enabled, false)
      INTO v_enabled
      FROM public.monetization_runtime_flags AS flags
     WHERE flags.singleton = true;

    RETURN jsonb_build_object(
        'enabled', (v_enabled AND v_count BETWEEN 6 AND 8),
        'provider', 'google_play',
        'products', v_products,
        'gift_limits', jsonb_build_object(
            'max_coins_per_gift', 5000,
            'max_coins_per_day', 25000,
            'max_coins_per_week', 0,
            'max_gifts_per_minute', 20,
            'high_value_confirmation_coins', 900
        )
    );
END;
$$;

REVOKE ALL ON FUNCTION public.billing_capabilities() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.billing_capabilities() TO authenticated;

-- Service-role-only credit primitive.  The caller must have already verified
-- the purchase with Google Play and must pass the server-computed SHA-256
-- token hash plus the catalog coin amount.  The advisory lock and unique hash
-- make retries/replayed callbacks idempotent.
CREATE OR REPLACE FUNCTION public.credit_verified_google_play_purchase(
    p_user_id UUID,
    p_product_id TEXT,
    p_purchase_token_hash TEXT,
    p_order_id TEXT DEFAULT NULL,
    p_coins INT DEFAULT NULL,
    p_purchased_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS TABLE (
    status TEXT,
    credited BOOLEAN,
    coins INT,
    balance_diamonds INT,
    purchase_id UUID,
    idempotent_replay BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_product billing_coin_products%ROWTYPE;
    v_prior billing_purchases%ROWTYPE;
    v_wallet wallets%ROWTYPE;
    v_purchase_id UUID;
    v_coins INT;
    v_balance INT;
    v_enabled BOOLEAN := false;
BEGIN
    IF p_user_id IS NULL OR p_product_id IS NULL OR p_purchase_token_hash IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'GOOGLE_PLAY_CREDIT_INVALID', ERRCODE = '22023';
    END IF;
    IF p_purchase_token_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION USING MESSAGE = 'GOOGLE_PLAY_TOKEN_HASH_INVALID', ERRCODE = '22023';
    END IF;

    SELECT COALESCE(flags.coin_purchases_enabled, false)
      INTO v_enabled
      FROM public.monetization_runtime_flags AS flags
     WHERE flags.singleton = true;
    IF v_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION USING MESSAGE = 'COIN_PURCHASES_DISABLED', ERRCODE = '42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(p_purchase_token_hash, 0));

    SELECT * INTO v_product
      FROM public.billing_coin_products
     WHERE product_id = btrim(p_product_id)
       AND is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING MESSAGE = 'GOOGLE_PLAY_PRODUCT_NOT_CONFIGURED', ERRCODE = '22023';
    END IF;
    v_coins := v_product.coins;
    IF p_coins IS NOT NULL AND p_coins <> v_coins THEN
        RAISE EXCEPTION USING MESSAGE = 'GOOGLE_PLAY_COIN_AMOUNT_MISMATCH', ERRCODE = '22023';
    END IF;

    SELECT * INTO v_prior
      FROM public.billing_purchases
     WHERE purchase_token_hash = p_purchase_token_hash
     FOR UPDATE;
    IF FOUND THEN
        IF v_prior.user_id <> p_user_id OR v_prior.product_id <> v_product.product_id THEN
            RAISE EXCEPTION USING MESSAGE = 'GOOGLE_PLAY_PURCHASE_ALREADY_CLAIMED', ERRCODE = '23505';
        END IF;
        SELECT * INTO v_wallet FROM public.wallets WHERE user_id = p_user_id;
        RETURN QUERY SELECT v_prior.status, false, v_prior.coins,
            COALESCE(v_wallet.balance_diamonds, 0), v_prior.id, true;
        RETURN;
    END IF;

    INSERT INTO public.wallets(user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, 0, 0, 0)
    ON CONFLICT (user_id) DO NOTHING;
    SELECT * INTO v_wallet
      FROM public.wallets
     WHERE user_id = p_user_id
     FOR UPDATE;
    v_balance := v_wallet.balance_diamonds + v_coins;
    UPDATE public.wallets
       SET balance_diamonds = v_balance, updated_at = now()
     WHERE user_id = p_user_id;

    v_purchase_id := gen_random_uuid();
    INSERT INTO public.billing_purchases(
        id, user_id, product_id, purchase_token_hash, order_id, coins,
        status, purchased_at, credited_at
    ) VALUES (
        v_purchase_id, p_user_id, v_product.product_id, p_purchase_token_hash,
        NULLIF(btrim(p_order_id), ''), v_coins, 'credited',
        p_purchased_at, now()
    );

    INSERT INTO public.wallet_transactions(
        user_id, type, amount_diamonds, amount_credits,
        balance_after_diamonds, balance_after_credits, reference_id, description
    ) VALUES (
        p_user_id, 'deposit', v_coins, 0,
        v_balance, v_wallet.balance_credits, v_purchase_id,
        'Crédito verificado: google_play'
    );

    RETURN QUERY SELECT 'credited'::TEXT, true, v_coins, v_balance,
        v_purchase_id, false;
END;
$$;

REVOKE ALL ON FUNCTION public.credit_verified_google_play_purchase(UUID, TEXT, TEXT, TEXT, INT, TIMESTAMPTZ)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.credit_verified_google_play_purchase(UUID, TEXT, TEXT, TEXT, INT, TIMESTAMPTZ)
    TO service_role;
