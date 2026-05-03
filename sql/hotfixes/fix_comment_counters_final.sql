-- =====================================================
-- FIX COMMENT COUNTERS AND TRIGGERS
-- Eliminates duplicates and synchronizes counts
-- =====================================================

BEGIN;

-- 1. CLEANUP OLD TRIGGERS AND FUNCTIONS
DROP TRIGGER IF EXISTS trigger_update_post_comment_count ON public.post_comments;
DROP TRIGGER IF EXISTS on_comment_created ON public.post_comments;

DROP FUNCTION IF EXISTS update_post_comment_count();
DROP FUNCTION IF EXISTS public.increment_comment_count();

-- 2. CREATE UNIFIED ROBUST FUNCTION
CREATE OR REPLACE FUNCTION public.sync_post_comment_count()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        UPDATE public.posts
        SET comment_count = (SELECT count(*) FROM public.post_comments WHERE post_id = NEW.post_id)
        WHERE id = NEW.post_id;
        RETURN NEW;
    ELSIF (TG_OP = 'DELETE') THEN
        UPDATE public.posts
        SET comment_count = (SELECT count(*) FROM public.post_comments WHERE post_id = OLD.post_id)
        WHERE id = OLD.post_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. CREATE NEW TRIGGER
CREATE TRIGGER tr_sync_post_comment_count
    AFTER INSERT OR DELETE ON public.post_comments
    FOR EACH ROW EXECUTE FUNCTION public.sync_post_comment_count();

-- 4. RECALCULATE ALL EXISTING POSTS
-- This ensures the UI is fixed even for existing bugged posts
UPDATE public.posts p
SET comment_count = (
    SELECT count(*) 
    FROM public.post_comments c 
    WHERE c.post_id = p.id
);

COMMIT;
