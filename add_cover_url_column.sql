-- ========================================
-- ADD COVER_URL COLUMN TO PROFILES
-- ========================================

-- Add cover_url column if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'profiles' 
        AND column_name = 'cover_url'
    ) THEN
        ALTER TABLE public.profiles ADD COLUMN cover_url TEXT;
        RAISE NOTICE 'Added cover_url column to profiles table';
    ELSE
        RAISE NOTICE 'cover_url column already exists';
    END IF;
END $$;

-- Verify the column was added
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' 
AND table_name = 'profiles'
AND column_name IN ('avatar_url', 'cover_url')
ORDER BY column_name;
