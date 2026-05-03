-- Final fix for Voice Access using PROFILES instead of users
-- This script includes FAIL-SAFE logic: deleting a report revokes access.

-- 1. Add column to public.profiles
ALTER TABLE public.profiles 
ADD COLUMN IF NOT EXISTS voice_access_granted BOOLEAN DEFAULT FALSE;

-- 2. Clean up old table
DROP TABLE IF EXISTS public.voice_access_requests CASCADE;

-- 3. Create requests table referencing profiles
CREATE TABLE IF NOT EXISTS public.voice_access_requests (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    status TEXT CHECK (status IN ('pending', 'approved', 'rejected')) DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id)
);

-- 4. Enable RLS
ALTER TABLE public.voice_access_requests ENABLE ROW LEVEL SECURITY;

DO $$ 
BEGIN
    DROP POLICY IF EXISTS "Users view own" ON public.voice_access_requests;
    DROP POLICY IF EXISTS "Users insert own" ON public.voice_access_requests;
    DROP POLICY IF EXISTS "Admins view all" ON public.voice_access_requests;
    DROP POLICY IF EXISTS "Admins update all" ON public.voice_access_requests;
EXCEPTION
    WHEN undefined_object THEN null;
END $$;

CREATE POLICY "Users view own" ON public.voice_access_requests FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users insert own" ON public.voice_access_requests FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Admins view all" ON public.voice_access_requests FOR SELECT USING (EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = true));
CREATE POLICY "Admins update all" ON public.voice_access_requests FOR UPDATE USING (EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = true));

-- 5. AUTOMATION: Isolated handled for Voice Access with REVOCATION on DELETE
-- This function handles granting, revoking, and cleanup if the report is deleted.

CREATE OR REPLACE FUNCTION handle_voice_access_automation()
RETURNS TRIGGER AS $$
BEGIN
    -- Only for Voice Access requests
    IF (TG_OP = 'DELETE') THEN
        IF (OLD.reason = 'Solicitud de Acceso a Voz') THEN
             UPDATE public.profiles SET voice_access_granted = FALSE WHERE id = OLD.reporter_id;
             DELETE FROM public.voice_access_requests WHERE user_id = OLD.reporter_id;
        END IF;
        RETURN OLD;
    END IF;

    -- For UPDATE
    IF (NEW.reason != 'Solicitud de Acceso a Voz') THEN
        RETURN NEW;
    END IF;

    IF (NEW.status IN ('finalizado', 'completado', 'resuelto', 'approved')) THEN
        UPDATE public.profiles SET voice_access_granted = TRUE WHERE id = NEW.reporter_id;
        UPDATE public.voice_access_requests SET status = 'approved', updated_at = NOW() WHERE user_id = NEW.reporter_id;
    ELSE
        UPDATE public.profiles SET voice_access_granted = FALSE WHERE id = NEW.reporter_id;
        IF (NEW.status IN ('rechazado', 'rejected')) THEN
            UPDATE public.voice_access_requests SET status = 'rejected', updated_at = NOW() WHERE user_id = NEW.reporter_id;
        ELSE
            UPDATE public.voice_access_requests SET status = 'pending', updated_at = NOW() WHERE user_id = NEW.reporter_id;
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger for UPDATE
DROP TRIGGER IF EXISTS on_voice_access_approval ON public.reports;
CREATE TRIGGER on_voice_access_approval
AFTER UPDATE ON public.reports
FOR EACH ROW
EXECUTE FUNCTION handle_voice_access_automation();

-- Trigger for DELETE (Surgical cleanup)
DROP TRIGGER IF EXISTS on_voice_access_deletion ON public.reports;
CREATE TRIGGER on_voice_access_deletion
AFTER DELETE ON public.reports
FOR EACH ROW
EXECUTE FUNCTION handle_voice_access_automation();
