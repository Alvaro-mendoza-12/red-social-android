-- Conectate Sprint 1-5 final contract
-- Safe to run more than once. Adds profile personalization, story highlights,
-- creator program primitives, and payout/earnings tables used by monetization.
-- Wallet/gifts base tables live in 20260425_* migrations; RPC hardening for
-- those tables is finalized in 20260506_wallet_gifts_rpc_security_hardening.sql.
-- Premium/ads RLS and subscribe_to_plan hardening are finalized in
-- 20260506_monetization_premium_ads_security_hardening.sql.
-- Post comment audio storage hardening is finalized in add_audio_to_post_comments.sql.
-- Chat favorite preference hardening is finalized in 20260506_chat_favorites_preferences_2026.sql.
-- Per-user chat archive preference and secure_set_chat_preferences archive
-- support are finalized in 20260506_chat_archive_preferences_2026.sql.

-- Profile personalization: Discord-style editable profile fields.
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS linked_games TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS game_logo_urls JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS favorite_music_url TEXT,
    ADD COLUMN IF NOT EXISTS favorite_book TEXT,
    ADD COLUMN IF NOT EXISTS favorite_sport TEXT,
    ADD COLUMN IF NOT EXISTS career TEXT,
    ADD COLUMN IF NOT EXISTS availability_schedule TEXT,
    ADD COLUMN IF NOT EXISTS last_seen_visibility TEXT NOT NULL DEFAULT 'everyone',
    ADD COLUMN IF NOT EXISTS online_status_visibility TEXT NOT NULL DEFAULT 'everyone';

DO $$
BEGIN
    ALTER TABLE public.profiles
        ADD CONSTRAINT profiles_last_seen_visibility_check
        CHECK (last_seen_visibility IN ('everyone', 'contacts', 'nobody'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
    ALTER TABLE public.profiles
        ADD CONSTRAINT profiles_online_status_visibility_check
        CHECK (online_status_visibility IN ('everyone', 'contacts', 'nobody'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_profiles_interests_gin ON public.profiles USING GIN (interests);
CREATE INDEX IF NOT EXISTS idx_profiles_linked_games_gin ON public.profiles USING GIN (linked_games);

-- Stories highlights: saved moments grouped on the profile.
CREATE TABLE IF NOT EXISTS public.moment_highlights (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (char_length(trim(title)) BETWEEN 1 AND 60),
    cover_media_url TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.moment_highlight_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    highlight_id UUID NOT NULL REFERENCES public.moment_highlights(id) ON DELETE CASCADE,
    moment_id UUID NOT NULL REFERENCES public.moments(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(highlight_id, moment_id)
);

ALTER TABLE public.moment_highlights ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moment_highlight_items ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS moment_highlights_select_policy ON public.moment_highlights;
CREATE POLICY moment_highlights_select_policy ON public.moment_highlights
    FOR SELECT USING (is_public = TRUE OR auth.uid() = user_id);

DROP POLICY IF EXISTS moment_highlights_insert_policy ON public.moment_highlights;
CREATE POLICY moment_highlights_insert_policy ON public.moment_highlights
    FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS moment_highlights_update_policy ON public.moment_highlights;
CREATE POLICY moment_highlights_update_policy ON public.moment_highlights
    FOR UPDATE USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS moment_highlights_delete_policy ON public.moment_highlights;
CREATE POLICY moment_highlights_delete_policy ON public.moment_highlights
    FOR DELETE USING (auth.uid() = user_id);

DROP POLICY IF EXISTS moment_highlight_items_select_policy ON public.moment_highlight_items;
CREATE POLICY moment_highlight_items_select_policy ON public.moment_highlight_items
    FOR SELECT USING (
        EXISTS (
            SELECT 1
            FROM public.moment_highlights h
            WHERE h.id = highlight_id
              AND (h.is_public = TRUE OR h.user_id = auth.uid())
        )
    );

DROP POLICY IF EXISTS moment_highlight_items_write_policy ON public.moment_highlight_items;
CREATE POLICY moment_highlight_items_write_policy ON public.moment_highlight_items
    FOR ALL USING (
        EXISTS (
            SELECT 1
            FROM public.moment_highlights h
            WHERE h.id = highlight_id
              AND h.user_id = auth.uid()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM public.moment_highlights h
            WHERE h.id = highlight_id
              AND h.user_id = auth.uid()
        )
    );

CREATE INDEX IF NOT EXISTS idx_moment_highlights_user ON public.moment_highlights(user_id, sort_order, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_moment_highlight_items_highlight ON public.moment_highlight_items(highlight_id, sort_order, created_at DESC);

-- Creator program and monetization primitives.
CREATE TABLE IF NOT EXISTS public.creator_program_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'paused')),
    niche TEXT,
    portfolio_url TEXT,
    payout_country TEXT,
    audience_size INT NOT NULL DEFAULT 0 CHECK (audience_size >= 0),
    reviewed_by UUID REFERENCES public.profiles(id),
    reviewed_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id)
);

CREATE TABLE IF NOT EXISTS public.creator_payout_accounts (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    provider TEXT NOT NULL CHECK (provider IN ('stripe', 'paypal', 'bank', 'manual')),
    external_account_id TEXT,
    country TEXT,
    currency TEXT NOT NULL DEFAULT 'USD',
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'verified', 'disabled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.creator_earnings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL CHECK (source_type IN ('gift', 'ad', 'premium', 'bonus')),
    source_id UUID,
    gross_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    platform_fee_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    net_amount_credits NUMERIC(12,2) NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'available', 'paid', 'reversed')),
    available_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.creator_program_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.creator_payout_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.creator_earnings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS creator_program_select_policy ON public.creator_program_applications;
CREATE POLICY creator_program_select_policy ON public.creator_program_applications
    FOR SELECT USING (
        auth.uid() = user_id OR
        EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND COALESCE(p.is_admin, FALSE) = TRUE)
    );

DROP POLICY IF EXISTS creator_program_insert_policy ON public.creator_program_applications;
CREATE POLICY creator_program_insert_policy ON public.creator_program_applications
    FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS creator_program_update_policy ON public.creator_program_applications;
CREATE POLICY creator_program_update_policy ON public.creator_program_applications
    FOR UPDATE USING (
        (
            auth.uid() = user_id
            AND status IN ('pending', 'rejected')
        ) OR
        EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND COALESCE(p.is_admin, FALSE) = TRUE)
    )
    WITH CHECK (
        (
            auth.uid() = user_id
            AND status = 'pending'
        ) OR
        EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND COALESCE(p.is_admin, FALSE) = TRUE)
    );

DROP POLICY IF EXISTS creator_payout_accounts_owner_policy ON public.creator_payout_accounts;
CREATE POLICY creator_payout_accounts_owner_policy ON public.creator_payout_accounts
    FOR ALL USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS creator_earnings_select_policy ON public.creator_earnings;
CREATE POLICY creator_earnings_select_policy ON public.creator_earnings
    FOR SELECT USING (
        auth.uid() = creator_id OR
        EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND COALESCE(p.is_admin, FALSE) = TRUE)
    );

CREATE INDEX IF NOT EXISTS idx_creator_program_status ON public.creator_program_applications(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_earnings_creator_status ON public.creator_earnings(creator_id, status, created_at DESC);
