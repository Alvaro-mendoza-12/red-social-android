-- Secure one-time web login pairings (WhatsApp Web style)

CREATE OR REPLACE FUNCTION public.tg_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS public.web_login_pairings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nonce TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'consumed', 'expired')),
    device_label TEXT,
    approved_by_user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    approved_by_email TEXT,
    session_payload JSONB,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '5 minutes'),
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_web_login_pairings_status_expires
    ON public.web_login_pairings(status, expires_at DESC);

CREATE INDEX IF NOT EXISTS idx_web_login_pairings_approved_by_user
    ON public.web_login_pairings(approved_by_user_id, created_at DESC);

DROP TRIGGER IF EXISTS trg_web_login_pairings_updated_at ON public.web_login_pairings;
CREATE TRIGGER trg_web_login_pairings_updated_at
BEFORE UPDATE ON public.web_login_pairings
FOR EACH ROW
EXECUTE FUNCTION public.tg_set_updated_at();

ALTER TABLE public.web_login_pairings ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.web_login_pairings FROM anon, authenticated;
