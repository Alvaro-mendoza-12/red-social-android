-- Seguridad transaccional para regalos Novvin (Supabase directo).
-- El cliente nunca decide el precio: la función bloquea el wallet, consulta
-- virtual_gifts y registra débito, regalo e ingreso pendiente en una transacción.

ALTER TABLE public.gift_transactions
    ADD COLUMN IF NOT EXISTS idempotency_key UUID,
    ADD COLUMN IF NOT EXISTS earning_id UUID,
    ADD COLUMN IF NOT EXISTS sender_balance_after INT,
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'committed';

CREATE UNIQUE INDEX IF NOT EXISTS gift_transactions_idempotency_key_uidx
    ON public.gift_transactions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS gift_transactions_sender_created_idx
    ON public.gift_transactions(sender_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.creator_earnings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    source_type TEXT NOT NULL,
    source_id UUID,
    live_stream_id UUID REFERENCES public.live_streams(id) ON DELETE SET NULL,
    gross_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    net_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    amount_diamonds INT NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ
);

ALTER TABLE public.creator_earnings
    ADD COLUMN IF NOT EXISTS creator_id UUID,
    ADD COLUMN IF NOT EXISTS sender_id UUID,
    ADD COLUMN IF NOT EXISTS source_type TEXT,
    ADD COLUMN IF NOT EXISTS source_id UUID,
    ADD COLUMN IF NOT EXISTS live_stream_id UUID,
    ADD COLUMN IF NOT EXISTS gross_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS amount_diamonds INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS creator_earnings_creator_status_idx
    ON public.creator_earnings(creator_id, status, created_at DESC);

ALTER TABLE public.creator_earnings ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
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
END $$;

-- Retira la variante heredada que confiaba en p_sender_id y no era idempotente.
DROP FUNCTION IF EXISTS public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID);
DROP FUNCTION IF EXISTS public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID, UUID, BOOLEAN);

CREATE FUNCTION public.send_virtual_gift(
    p_sender_id UUID,
    p_receiver_id UUID,
    p_gift_id UUID,
    p_message TEXT,
    p_live_stream_id UUID,
    p_idempotency_key UUID,
    p_is_public BOOLEAN
)
RETURNS TABLE (
    transaction_id UUID,
    earning_id UUID,
    sender_balance_diamonds INT,
    gift_id UUID,
    gift_name TEXT,
    gift_image_url TEXT,
    gift_animation_url TEXT,
    sender_id UUID,
    sender_name TEXT,
    receiver_id UUID,
    live_stream_id UUID,
    is_public BOOLEAN,
    idempotent_replay BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_actor_id UUID := auth.uid();
    v_gift public.virtual_gifts%ROWTYPE;
    v_prior public.gift_transactions%ROWTYPE;
    v_sender_balance INT;
    v_transaction_id UUID;
    v_earning_id UUID;
    v_sender_name TEXT := 'Usuario';
    v_is_public BOOLEAN := COALESCE(p_is_public, true);
    v_gifts_last_minute INT;
    v_spent_last_day BIGINT;
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'AUTH_REQUIRED', ERRCODE = '42501';
    END IF;
    IF p_sender_id IS NULL OR p_sender_id <> v_actor_id THEN
        RAISE EXCEPTION USING MESSAGE = 'ACTOR_ID_MISMATCH', ERRCODE = '42501';
    END IF;
    IF p_receiver_id IS NULL OR p_gift_id IS NULL OR p_idempotency_key IS NULL THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_REQUEST_INVALID', ERRCODE = '22023';
    END IF;
    IF p_receiver_id = v_actor_id THEN
        RAISE EXCEPTION USING MESSAGE = 'SELF_GIFT_FORBIDDEN', ERRCODE = '22023';
    END IF;

    -- Serializa reintentos de la misma operación antes de consultar el recibo.
    PERFORM pg_advisory_xact_lock(hashtextextended(p_idempotency_key::TEXT, 0));

    SELECT gt.*
      INTO v_prior
      FROM public.gift_transactions AS gt
     WHERE gt.idempotency_key = p_idempotency_key
     LIMIT 1;

    IF FOUND THEN
        IF v_prior.sender_id <> v_actor_id
           OR v_prior.receiver_id <> p_receiver_id
           OR v_prior.gift_id <> p_gift_id
           OR v_prior.live_stream_id IS DISTINCT FROM p_live_stream_id
           OR v_prior.is_public IS DISTINCT FROM v_is_public THEN
            RAISE EXCEPTION USING MESSAGE = 'GIFT_IDEMPOTENCY_CONFLICT', ERRCODE = '23505';
        END IF;

        SELECT COALESCE(NULLIF(p.display_name, ''), NULLIF(p.username, ''), 'Usuario')
          INTO v_sender_name
          FROM public.profiles AS p
         WHERE p.id = v_actor_id;

        RETURN QUERY
        SELECT v_prior.id,
               v_prior.earning_id,
               COALESCE(v_prior.sender_balance_after, w.balance_diamonds),
               vg.id,
               vg.name,
               vg.image_url,
               vg.animation_url,
               v_prior.sender_id,
               COALESCE(v_sender_name, 'Usuario'),
               v_prior.receiver_id,
               v_prior.live_stream_id,
               v_prior.is_public,
               true
          FROM public.virtual_gifts AS vg
          JOIN public.wallets AS w ON w.user_id = v_actor_id
         WHERE vg.id = v_prior.gift_id;
        RETURN;
    END IF;

    SELECT vg.*
      INTO v_gift
      FROM public.virtual_gifts AS vg
     WHERE vg.id = p_gift_id
       AND vg.is_active = true
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_NOT_FOUND', ERRCODE = 'P0002';
    END IF;
    IF v_gift.price_diamonds <= 0 THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_PRICE_INVALID', ERRCODE = '22023';
    END IF;
    IF v_gift.price_diamonds > 5000 THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_VALUE_LIMIT_EXCEEDED', ERRCODE = '22023';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM auth.users AS u WHERE u.id = p_receiver_id) THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_RECEIVER_NOT_FOUND', ERRCODE = 'P0002';
    END IF;

    IF p_live_stream_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
          FROM public.live_streams AS ls
         WHERE ls.id = p_live_stream_id
           AND ls.host_user_id = p_receiver_id
           AND ls.is_active = true
    ) THEN
        RAISE EXCEPTION USING MESSAGE = 'LIVE_STREAM_RECEIVER_MISMATCH', ERRCODE = '22023';
    END IF;

    INSERT INTO public.wallets(user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (v_actor_id, 0, 0, 0)
    ON CONFLICT (user_id) DO NOTHING;

    SELECT w.balance_diamonds
      INTO v_sender_balance
      FROM public.wallets AS w
     WHERE w.user_id = v_actor_id
     FOR UPDATE;

    -- El bloqueo del wallet hace que los límites y el débito sean atómicos aun
    -- cuando dos dispositivos envían al mismo tiempo.
    SELECT COUNT(*)::INT,
           COALESCE(SUM(gt.amount_diamonds) FILTER (
               WHERE gt.created_at >= now() - INTERVAL '24 hours'
           ), 0)::BIGINT
      INTO v_gifts_last_minute, v_spent_last_day
      FROM public.gift_transactions AS gt
     WHERE gt.sender_id = v_actor_id
       AND gt.status = 'committed'
       AND gt.created_at >= now() - INTERVAL '24 hours';

    SELECT COUNT(*)::INT
      INTO v_gifts_last_minute
      FROM public.gift_transactions AS gt
     WHERE gt.sender_id = v_actor_id
       AND gt.status = 'committed'
       AND gt.created_at >= now() - INTERVAL '1 minute';

    IF v_gifts_last_minute >= 20 THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_RATE_LIMIT_EXCEEDED', ERRCODE = 'P0001';
    END IF;
    IF v_spent_last_day + v_gift.price_diamonds > 25000 THEN
        RAISE EXCEPTION USING MESSAGE = 'GIFT_DAILY_LIMIT_EXCEEDED', ERRCODE = 'P0001';
    END IF;
    IF v_sender_balance < v_gift.price_diamonds THEN
        RAISE EXCEPTION USING MESSAGE = 'INSUFFICIENT_DIAMONDS', ERRCODE = 'P0001';
    END IF;

    v_sender_balance := v_sender_balance - v_gift.price_diamonds;
    v_transaction_id := p_idempotency_key;
    v_earning_id := gen_random_uuid();

    UPDATE public.wallets
       SET balance_diamonds = v_sender_balance,
           updated_at = now()
     WHERE user_id = v_actor_id;

    INSERT INTO public.creator_earnings(
        id, creator_id, sender_id, source_type, source_id, live_stream_id,
        gross_amount_credits, net_amount_credits, amount_diamonds, status
    ) VALUES (
        v_earning_id, p_receiver_id, v_actor_id, 'virtual_gift', p_gift_id,
        p_live_stream_id, 0, 0, v_gift.price_diamonds, 'pending'
    );

    INSERT INTO public.gift_transactions(
        id, idempotency_key, earning_id, sender_id, receiver_id, gift_id,
        live_stream_id, message, amount_diamonds, amount_credits,
        sender_balance_after, is_public, status
    ) VALUES (
        v_transaction_id, p_idempotency_key, v_earning_id, v_actor_id,
        p_receiver_id, p_gift_id, p_live_stream_id, NULLIF(btrim(p_message), ''),
        v_gift.price_diamonds, 0, v_sender_balance, v_is_public, 'committed'
    );

    SELECT COALESCE(NULLIF(p.display_name, ''), NULLIF(p.username, ''), 'Usuario')
      INTO v_sender_name
      FROM public.profiles AS p
     WHERE p.id = v_actor_id;

    RETURN QUERY SELECT
        v_transaction_id,
        v_earning_id,
        v_sender_balance,
        v_gift.id,
        v_gift.name,
        v_gift.image_url,
        v_gift.animation_url,
        v_actor_id,
        COALESCE(v_sender_name, 'Usuario'),
        p_receiver_id,
        p_live_stream_id,
        v_is_public,
        false;
END;
$$;

REVOKE ALL ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID, UUID, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID, UUID, BOOLEAN) TO authenticated;

CREATE OR REPLACE FUNCTION public.get_virtual_gift_receipt(p_transaction_id UUID)
RETURNS TABLE (
    id UUID,
    live_stream_id UUID,
    is_public BOOLEAN,
    sender_id UUID,
    sender_name TEXT,
    receiver_id UUID,
    gift_id UUID,
    gift_name TEXT,
    gift_image_url TEXT,
    gift_animation_url TEXT,
    amount_diamonds INT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT gt.id,
           gt.live_stream_id,
           gt.is_public,
           gt.sender_id,
           COALESCE(NULLIF(p.display_name, ''), NULLIF(p.username, ''), 'Usuario'),
           gt.receiver_id,
           vg.id,
           vg.name,
           vg.image_url,
           vg.animation_url,
           gt.amount_diamonds
      FROM public.gift_transactions AS gt
      JOIN public.virtual_gifts AS vg ON vg.id = gt.gift_id
      LEFT JOIN public.profiles AS p ON p.id = gt.sender_id
     WHERE gt.id = p_transaction_id
       AND gt.status = 'committed'
       AND gt.sender_id = auth.uid()
     LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.get_virtual_gift_receipt(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_virtual_gift_receipt(UUID) TO authenticated;
