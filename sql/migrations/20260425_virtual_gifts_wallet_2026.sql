-- ============================================
-- Virtual Gifts & Wallet System
-- FECHA: 2026-04-25
-- PURPOSE: Monetización con diamantes, regalos virtuales, wallet
-- ============================================

-- Wallet de usuario
CREATE TABLE IF NOT EXISTS wallets (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    balance_diamonds INT NOT NULL DEFAULT 0,
    balance_credits DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_spent DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE wallets ENABLE ROW LEVEL SECURITY;

-- Políticas: solo el usuario puede ver/editar su wallet
CREATE POLICY "wallet_select_own" ON wallets
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "wallet_update_own" ON wallets
    FOR UPDATE USING (auth.uid() = user_id);

-- Regalos virtuales disponibles
CREATE TABLE IF NOT EXISTS virtual_gifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    image_url TEXT NOT NULL,
    price_diamonds INT NOT NULL,
    price_credits DECIMAL(10,2) NOT NULL,
    animation_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Asegurar constraint UNIQUE en name
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'virtual_gifts_name_key') THEN
        ALTER TABLE virtual_gifts ADD CONSTRAINT virtual_gifts_name_key UNIQUE (name);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_virtual_gifts_active ON virtual_gifts(is_active, sort_order);

-- Transacciones de regalos
CREATE TABLE IF NOT EXISTS gift_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id UUID NOT NULL REFERENCES auth.users(id),
    receiver_id UUID NOT NULL REFERENCES auth.users(id),
    gift_id UUID NOT NULL REFERENCES virtual_gifts(id),
    live_stream_id UUID REFERENCES live_streams(id),
    message TEXT,
    amount_diamonds INT NOT NULL,
    amount_credits DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gift_transactions_receiver ON gift_transactions(receiver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gift_transactions_sender ON gift_transactions(sender_id, created_at DESC);

-- Historial de transacciones de wallet (depósitos, retiros, compras)
CREATE TABLE IF NOT EXISTS wallet_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id),
    type TEXT NOT NULL CHECK (type IN ('deposit', 'withdraw', 'purchase', 'refund', 'payout')),
    amount_diamonds INT NOT NULL DEFAULT 0,
    amount_credits DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    balance_after_diamonds INT NOT NULL,
    balance_after_credits DECIMAL(10,2) NOT NULL,
    reference_id UUID, -- link a gift_transactions, payout, etc.
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wallet_transactions_user ON wallet_transactions(user_id, created_at DESC);

-- Planes de suscripción (premium)
CREATE TABLE IF NOT EXISTS subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    price_monthly DECIMAL(10,2) NOT NULL,
    price_yearly DECIMAL(10,2) NOT NULL,
    features JSONB NOT NULL DEFAULT '[]',
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0
);

-- Asegurar constraint UNIQUE en name
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subscription_plans_name_key') THEN
        ALTER TABLE subscription_plans ADD CONSTRAINT subscription_plans_name_key UNIQUE (name);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES subscription_plans(id),
    status TEXT NOT NULL CHECK (status IN ('active', 'canceled', 'expired', 'pending')),
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, plan_id, status)
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user ON user_subscriptions(user_id, status);

-- Anuncios (Ads platform)
CREATE TABLE IF NOT EXISTS ad_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    advertiser_id UUID NOT NULL REFERENCES auth.users(id),
    name TEXT NOT NULL,
    creative_url TEXT NOT NULL,
    target_url TEXT NOT NULL,
    budget DECIMAL(10,2) NOT NULL,
    spent DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('draft', 'active', 'paused', 'completed')),
    targeting_hashtags TEXT[] DEFAULT '{}',
    targeting_countries TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ad_impressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES ad_campaigns(id),
    user_id UUID REFERENCES auth.users(id),
    post_id UUID NULL REFERENCES posts(id), -- si el ad es un post promocionado
    impression_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ad_impressions_campaign ON ad_impressions(campaign_id, impression_at DESC);

-- Insertar regalos virtuales básicos
INSERT INTO virtual_gifts (name, description, image_url, price_diamonds, price_credits, animation_url, sort_order) VALUES
('Corazón', 'Un corazón para expresar tu cariño', 'https://example.com/gifts/heart.png', 10, 1.99, 'https://example.com/animations/heart.json', 1),
('Rosa', 'Una hermosa rosa virtual', 'https://example.com/gifts/rose.png', 25, 4.99, 'https://example.com/animations/rose.json', 2),
('Trofeo', '¡Felicitaciones por tu victoria!', 'https://example.com/gifts/trophy.png', 50, 9.99, 'https://example.com/animations/trophy.json', 3),
('Fuegos Artificiales', '¡Celebración épica!', 'https://example.com/gifts/fireworks.png', 100, 19.99, 'https://example.com/animations/fireworks.json', 4),
('Superestrella', 'Eres una estrella', 'https://example.com/gifts/star.png', 200, 39.99, 'https://example.com/animations/star.json', 5)
ON CONFLICT (name) DO NOTHING;

-- Insertar planes de suscripción
INSERT INTO subscription_plans (name, description, price_monthly, price_yearly, features, sort_order) VALUES
('Premium Básico', 'Funciones premium básicas', 4.99, 49.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs"]', 1),
('Premium Pro', 'Para creadores profesionales', 9.99, 99.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs", "analytics", "monetization"]', 2),
('Premium Creator', 'Para super creadores', 19.99, 199.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs", "analytics", "monetization", "priority_support"]', 3)
ON CONFLICT (name) DO NOTHING;