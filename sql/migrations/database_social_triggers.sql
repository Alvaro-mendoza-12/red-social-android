-- ========================================
-- SOCIAL TRIGGERS: AUTOMATIC COUNTERS
-- ========================================

-- 1. Function to handle follow state changes
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

-- 2. Create Triggers
DROP TRIGGER IF EXISTS tr_on_follow_insert ON public.follows;
CREATE TRIGGER tr_on_follow_insert
    AFTER INSERT ON public.follows
    FOR EACH ROW EXECUTE FUNCTION public.handle_follow_stats();

DROP TRIGGER IF EXISTS tr_on_follow_delete ON public.follows;
CREATE TRIGGER tr_on_follow_delete
    AFTER DELETE ON public.follows
    FOR EACH ROW EXECUTE FUNCTION public.handle_follow_stats();
