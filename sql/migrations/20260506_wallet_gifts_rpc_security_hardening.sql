-- ============================================
-- Wallet & Gifts RPC Security Hardening
-- FECHA: 2026-05-06
-- PURPOSE: Ejecutar wallet/gifts desde app cliente sin permitir manipulacion de saldos.
-- ============================================

CREATE OR REPLACE FUNCTION public.wallet_get_or_create()
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
) AS $$
DECLARE
    current_user_id UUID := auth.uid();
BEGIN
    IF current_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (current_user_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = current_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

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
) AS $$
DECLARE
    new_balance_diamonds INT;
    current_credits DECIMAL(10,2);
    current_total_spent DECIMAL(10,2);
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'Target user is required';
    END IF;

    IF COALESCE(p_amount, 0) <= 0 THEN
        RAISE EXCEPTION 'Amount must be greater than zero';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role' AND NOT caller_is_admin THEN
        RAISE EXCEPTION 'Only service role or admins can add diamonds';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, p_amount, 0.00, 0.00)
    ON CONFLICT (user_id) DO UPDATE SET
        balance_diamonds = public.wallets.balance_diamonds + p_amount,
        updated_at = now()
    RETURNING public.wallets.balance_diamonds, public.wallets.balance_credits, public.wallets.total_spent
    INTO new_balance_diamonds, current_credits, current_total_spent;

    INSERT INTO public.wallet_transactions (
        user_id,
        type,
        amount_diamonds,
        balance_after_diamonds,
        balance_after_credits,
        description
    ) VALUES (
        p_user_id,
        'deposit',
        p_amount,
        new_balance_diamonds,
        current_credits,
        'Diamantes agregados desde: ' || COALESCE(NULLIF(trim(p_source), ''), 'purchase')
    );

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

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
) AS $$
DECLARE
    current_balance INT;
    new_balance INT;
    current_credits DECIMAL(10,2);
    current_total_spent DECIMAL(10,2);
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'Target user is required';
    END IF;

    IF COALESCE(p_amount, 0) <= 0 THEN
        RAISE EXCEPTION 'Amount must be greater than zero';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'Users can only spend their own diamonds';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    SELECT w.balance_diamonds, w.balance_credits, w.total_spent
    INTO current_balance, current_credits, current_total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id
    FOR UPDATE;

    IF COALESCE(current_balance, 0) < p_amount THEN
        RAISE EXCEPTION 'Saldo insuficiente. Balance actual: %, requerido: %',
            COALESCE(current_balance, 0),
            p_amount;
    END IF;

    UPDATE public.wallets SET
        balance_diamonds = balance_diamonds - p_amount,
        total_spent = total_spent + (p_amount * 0.1),
        updated_at = now()
    WHERE public.wallets.user_id = p_user_id
    RETURNING public.wallets.balance_diamonds, public.wallets.balance_credits, public.wallets.total_spent
    INTO new_balance, current_credits, current_total_spent;

    INSERT INTO public.wallet_transactions (
        user_id,
        type,
        amount_diamonds,
        balance_after_diamonds,
        balance_after_credits,
        reference_id,
        description
    ) VALUES (
        p_user_id,
        'purchase',
        -p_amount,
        new_balance,
        current_credits,
        p_reference_id,
        'Compra: ' || COALESCE(NULLIF(trim(p_purpose), ''), 'wallet')
    );

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.send_virtual_gift(
    p_sender_id UUID,
    p_receiver_id UUID,
    p_gift_id UUID,
    p_message TEXT DEFAULT NULL,
    p_live_stream_id UUID DEFAULT NULL
)
RETURNS TABLE (
    transaction_id UUID,
    sender_balance_diamonds INT,
    receiver_wallet_created BOOLEAN
) AS $$
DECLARE
    gift_price INT;
    tx_id UUID;
    sender_balance INT;
    inserted_rows INT := 0;
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_sender_id IS NULL OR p_receiver_id IS NULL OR p_gift_id IS NULL THEN
        RAISE EXCEPTION 'Sender, receiver and gift are required';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_sender_id THEN
        RAISE EXCEPTION 'Users can only send gifts from their own account';
    END IF;

    SELECT vg.price_diamonds
    INTO gift_price
    FROM public.virtual_gifts vg
    WHERE vg.id = p_gift_id
      AND vg.is_active = TRUE;

    IF gift_price IS NULL THEN
        RAISE EXCEPTION 'Regalo no encontrado o inactivo';
    END IF;

    SELECT spent.balance_diamonds
    INTO sender_balance
    FROM public.wallet_spend_diamonds(p_sender_id, gift_price, 'regalo_virtual', p_gift_id) spent;

    INSERT INTO public.gift_transactions (
        sender_id,
        receiver_id,
        gift_id,
        live_stream_id,
        message,
        amount_diamonds,
        amount_credits
    ) VALUES (
        p_sender_id,
        p_receiver_id,
        p_gift_id,
        p_live_stream_id,
        NULLIF(trim(COALESCE(p_message, '')), ''),
        gift_price,
        0.00
    ) RETURNING id INTO tx_id;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_receiver_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    GET DIAGNOSTICS inserted_rows = ROW_COUNT;

    RETURN QUERY SELECT tx_id, sender_balance, inserted_rows > 0;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.wallet_get_or_create() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.wallet_get_or_create() TO authenticated;
GRANT EXECUTE ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID) TO authenticated, service_role;
