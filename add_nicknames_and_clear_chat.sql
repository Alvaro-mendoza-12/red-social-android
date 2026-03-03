-- ======================================================
-- COMPLETE & IDEMPOTENT NICKNAMES AND CLEAR CHAT SUPPORT
-- ======================================================

-- 1. Create nicknames table for private nicknames
CREATE TABLE IF NOT EXISTS public.nicknames (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    nickname TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, target_user_id)
);

-- Ensure RLS is enabled (safe to run multiple times)
ALTER TABLE public.nicknames ENABLE ROW LEVEL SECURITY;

-- Policy: Drop if exists and recreate to ensure it matches the current logic
DROP POLICY IF EXISTS "Users can manage own nicknames" ON public.nicknames;
CREATE POLICY "Users can manage own nicknames" ON public.nicknames
    FOR ALL USING (auth.uid() = user_id);

-- 2. Add cleared_at to chat_participants (Idempotent column addition)
DO $$ 
BEGIN 
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name='chat_participants' AND column_name='cleared_at') THEN
        ALTER TABLE public.chat_participants ADD COLUMN cleared_at TIMESTAMP WITH TIME ZONE;
    END IF;
END $$;

-- 3. Trigger to update updated_at on nicknames table
DROP TRIGGER IF EXISTS update_nicknames_updated_at ON public.nicknames;
CREATE TRIGGER update_nicknames_updated_at BEFORE UPDATE ON public.nicknames
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
