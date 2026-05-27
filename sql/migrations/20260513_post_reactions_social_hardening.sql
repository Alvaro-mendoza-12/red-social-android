-- Sprint 3 Social: typed post reactions for production feed UX.
-- Safe to run multiple times on fresh or partially deployed databases.

CREATE TABLE IF NOT EXISTS public.post_reactions (
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reaction_type TEXT NOT NULL CHECK (reaction_type IN ('LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY', 'CARE', 'FIRE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS post_id UUID REFERENCES public.posts(id) ON DELETE CASCADE;
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS reaction_type TEXT NOT NULL DEFAULT 'LIKE';
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.post_reactions'::regclass
          AND conname = 'post_reactions_pkey'
    ) THEN
        ALTER TABLE public.post_reactions
            ADD CONSTRAINT post_reactions_pkey PRIMARY KEY (post_id, user_id);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'post_reactions primary key skipped: %', SQLERRM;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.post_reactions'::regclass
          AND conname = 'post_reactions_reaction_type_check'
    ) THEN
        ALTER TABLE public.post_reactions
            ADD CONSTRAINT post_reactions_reaction_type_check
            CHECK (reaction_type IN ('LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY', 'CARE', 'FIRE'));
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'post_reactions reaction_type check skipped: %', SQLERRM;
END $$;

CREATE INDEX IF NOT EXISTS idx_post_reactions_post_id ON public.post_reactions(post_id);
CREATE INDEX IF NOT EXISTS idx_post_reactions_user_id ON public.post_reactions(user_id);
CREATE INDEX IF NOT EXISTS idx_post_reactions_type ON public.post_reactions(reaction_type);

ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Post reactions are viewable by everyone" ON public.post_reactions;
CREATE POLICY "Post reactions are viewable by everyone" ON public.post_reactions
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "Users can insert own post reactions" ON public.post_reactions;
CREATE POLICY "Users can insert own post reactions" ON public.post_reactions
    FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own post reactions" ON public.post_reactions;
CREATE POLICY "Users can update own post reactions" ON public.post_reactions
    FOR UPDATE USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own post reactions" ON public.post_reactions;
CREATE POLICY "Users can delete own post reactions" ON public.post_reactions
    FOR DELETE USING (auth.uid() = user_id);

CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS update_post_reactions_updated_at ON public.post_reactions;
CREATE TRIGGER update_post_reactions_updated_at
    BEFORE UPDATE ON public.post_reactions
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();
