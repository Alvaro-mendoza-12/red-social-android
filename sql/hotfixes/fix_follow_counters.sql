-- ========================================
-- ADD MISSING COLUMNS AND FIX FOLLOW COUNTERS
-- ========================================

-- 1. Add followers_count column if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'profiles' 
        AND column_name = 'followers_count'
    ) THEN
        ALTER TABLE public.profiles ADD COLUMN followers_count INTEGER DEFAULT 0;
        RAISE NOTICE 'Added followers_count column';
    ELSE
        RAISE NOTICE 'followers_count column already exists';
    END IF;
END $$;

-- 2. Add following_count column if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'profiles' 
        AND column_name = 'following_count'
    ) THEN
        ALTER TABLE public.profiles ADD COLUMN following_count INTEGER DEFAULT 0;
        RAISE NOTICE 'Added following_count column';
    ELSE
        RAISE NOTICE 'following_count column already exists';
    END IF;
END $$;

-- 3. Recreate the triggers (idempotent)
CREATE OR REPLACE FUNCTION public.handle_follow_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        -- Increment following count for the user who followed
        UPDATE public.profiles
        SET following_count = following_count + 1
        WHERE id = NEW.follower_id;

        -- Increment followers count for the user being followed
        UPDATE public.profiles
        SET followers_count = followers_count + 1
        WHERE id = NEW.following_id;
        
    ELSIF (TG_OP = 'DELETE') THEN
        -- Decrement following count for the user who unfollowed
        UPDATE public.profiles
        SET following_count = GREATEST(following_count - 1, 0)
        WHERE id = OLD.follower_id;

        -- Decrement followers count for the user being unfollowed
        UPDATE public.profiles
        SET followers_count = GREATEST(followers_count - 1, 0)
        WHERE id = OLD.following_id;
    END IF;
    
    RETURN NULL; -- result is ignored since this is an AFTER trigger
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. Create Triggers
DROP TRIGGER IF EXISTS tr_on_follow_insert ON public.follows;
CREATE TRIGGER tr_on_follow_insert
    AFTER INSERT ON public.follows
    FOR EACH ROW EXECUTE FUNCTION public.handle_follow_stats();

DROP TRIGGER IF EXISTS tr_on_follow_delete ON public.follows;
CREATE TRIGGER tr_on_follow_delete
    AFTER DELETE ON public.follows
    FOR EACH ROW EXECUTE FUNCTION public.handle_follow_stats();

-- 5. Recalculate ALL follow counters based on actual data
UPDATE public.profiles SET following_count = 0, followers_count = 0;

-- Update following_count (how many people this user follows)
UPDATE public.profiles p
SET following_count = (
    SELECT COUNT(*)
    FROM public.follows f
    WHERE f.follower_id = p.id
);

-- Update followers_count (how many people follow this user)
UPDATE public.profiles p
SET followers_count = (
    SELECT COUNT(*)
    FROM public.follows f
    WHERE f.following_id = p.id
);

-- 6. Verify the counts are correct
SELECT 
    p.id,
    p.display_name,
    p.email,
    p.following_count,
    p.followers_count,
    (SELECT COUNT(*) FROM follows WHERE follower_id = p.id) as actual_following,
    (SELECT COUNT(*) FROM follows WHERE following_id = p.id) as actual_followers
FROM public.profiles p
ORDER BY p.created_at DESC
LIMIT 20;
