-- ============================================
-- Wallet & Gifts RPC Functions
-- FECHA: 2026-04-25
-- PURPOSE: Operaciones atómicas de wallet y regalos
-- ============================================

-- RPC: Añadir diamantes al wallet (compra, bonus)
CREATE OR REPLACE FUNCTION wallet_add_diamonds(
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
BEGIN
    -- Insertar o actualizar wallet
    INSERT INTO wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, p_amount, 0.00, 0.00)
    ON CONFLICT (user_id) DO UPDATE SET
        balance_diamonds = wallets.balance_diamonds + p_amount,
        updated_at = now()
    RETURNING balance_diamonds, balance_credits, total_spent
    INTO new_balance_diamonds, current_credits, current_total_spent;

    -- Registrar transacción
    INSERT INTO wallet_transactions (
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, description
    ) VALUES (
        p_user_id, 'deposit', p_amount, new_balance_diamonds,
        current_credits, 'Diamantes agregados desde: ' || p_source
    );

    -- Devolver estado actual del wallet
    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Gastar diamantes (comprar regalo, etc.)
CREATE OR REPLACE FUNCTION wallet_spend_diamonds(
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
BEGIN
    -- Verificar saldo suficiente
    SELECT balance_diamonds, balance_credits, total_spent
    INTO current_balance, current_credits, current_total_spent
    FROM wallets
    WHERE user_id = p_user_id;

    IF current_balance < p_amount THEN
        RAISE EXCEPTION 'Saldo insuficiente. Balance actual: %, requerido: %', current_balance, p_amount;
    END IF;

    -- Actualizar balance
    UPDATE wallets SET
        balance_diamonds = balance_diamonds - p_amount,
        total_spent = total_spent + (p_amount * 0.1), -- asumir 0.1 USD por diamante
        updated_at = now()
    WHERE user_id = p_user_id
    RETURNING balance_diamonds, balance_credits, total_spent
    INTO new_balance, current_credits, current_total_spent;

    -- Registrar transacción
    INSERT INTO wallet_transactions (
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, reference_id, description
    ) VALUES (
        p_user_id, 'purchase', -p_amount, new_balance,
        current_credits, p_reference_id, 'Compra: ' || p_purpose
    );

    -- Devolver estado actual del wallet
    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Enviar regalo virtual
CREATE OR REPLACE FUNCTION send_virtual_gift(
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
    receiver_created BOOLEAN := FALSE;
BEGIN
    -- Obtener precio del regalo
    SELECT price_diamonds INTO gift_price
    FROM virtual_gifts
    WHERE id = p_gift_id AND is_active = true;

    IF gift_price IS NULL THEN
        RAISE EXCEPTION 'Regalo no encontrado o inactivo';
    END IF;

    -- Gastar diamantes del sender
    SELECT balance_diamonds INTO sender_balance
    FROM wallet_spend_diamonds(p_sender_id, gift_price, 'regalo_virtual', p_gift_id);

    -- Crear transacción de regalo
    INSERT INTO gift_transactions (
        sender_id, receiver_id, gift_id, live_stream_id, message,
        amount_diamonds, amount_credits
    ) VALUES (
        p_sender_id, p_receiver_id, p_gift_id, p_live_stream_id, p_message,
        gift_price, 0.00
    ) RETURNING id INTO tx_id;

    -- Crear wallet para receiver si no existe
    INSERT INTO wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_receiver_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    IF NOT FOUND THEN
        receiver_created := TRUE;
    END IF;

    -- Notificar al receiver (puedes implementar triggers para notificaciones push)
    -- INSERT INTO notifications ... (implementar según tu sistema de notificaciones)

    RETURN QUERY SELECT tx_id, sender_balance, receiver_created;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Obtener regalos recibidos por usuario
CREATE OR REPLACE FUNCTION get_user_received_gifts(
    p_user_id UUID,
    p_limit INT DEFAULT 50,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    gift_name TEXT,
    gift_image_url TEXT,
    sender_username TEXT,
    sender_display_name TEXT,
    sender_avatar TEXT,
    message TEXT,
    amount_diamonds INT,
    created_at TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        vg.name AS gift_name,
        vg.image_url AS gift_image_url,
        sp.username AS sender_username,
        sp.display_name AS sender_display_name,
        sp.avatar_url AS sender_avatar,
        gt.message,
        gt.amount_diamonds,
        gt.created_at
    FROM gift_transactions gt
    JOIN virtual_gifts vg ON gt.gift_id = vg.id
    JOIN profiles sp ON gt.sender_id = sp.id
    WHERE gt.receiver_id = p_user_id
    ORDER BY gt.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Obtener top donadores de un live stream
CREATE OR REPLACE FUNCTION get_live_stream_top_donors(
    p_live_stream_id UUID,
    p_limit INT DEFAULT 10
)
RETURNS TABLE (
    username TEXT,
    display_name TEXT,
    avatar_url TEXT,
    total_diamonds INT,
    total_gifts INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.username,
        p.display_name,
        p.avatar_url,
        SUM(gt.amount_diamonds)::INT AS total_diamonds,
        COUNT(gt.id)::INT AS total_gifts
    FROM gift_transactions gt
    JOIN profiles p ON gt.sender_id = p.id
    WHERE gt.live_stream_id = p_live_stream_id
    GROUP BY p.id, p.username, p.display_name, p.avatar_url
    ORDER BY total_diamonds DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- RPC: Suscribir usuario a plan premium
CREATE OR REPLACE FUNCTION subscribe_to_plan(
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
    plan_price DECIMAL(10,2);
    plan_name TEXT;
    sub_id UUID;
    period_start TIMESTAMPTZ;
    period_end TIMESTAMPTZ;
BEGIN
    -- Obtener info del plan
    SELECT price_monthly, name INTO plan_price, plan_name
    FROM subscription_plans
    WHERE id = p_plan_id AND is_active = true;

    IF plan_price IS NULL THEN
        RAISE EXCEPTION 'Plan no encontrado o inactivo';
    END IF;

    -- Calcular período (1 mes por defecto)
    period_start := now();
    period_end := period_start + INTERVAL '1 month';

    -- Crear suscripción
    INSERT INTO user_subscriptions (
        user_id, plan_id, status, current_period_start, current_period_end
    ) VALUES (
        p_user_id, p_plan_id, 'active', period_start, period_end
    ) RETURNING id INTO sub_id;

    -- Gastar del wallet si es método wallet
    IF p_payment_method = 'wallet' THEN
        -- Convertir precio a diamantes (asumir 1 diamante = 0.1 USD)
        PERFORM wallet_spend_diamonds(p_user_id, (plan_price * 10)::INT, 'suscripción_premium', sub_id);
    END IF;

    RETURN QUERY SELECT sub_id, plan_name, period_start, period_end;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;
